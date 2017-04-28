/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.spi.ConstantProperty;
import com.facebook.presto.spi.LocalProperty;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.sql.planner.Partitioning;
import com.facebook.presto.sql.planner.PartitioningHandle;
import com.facebook.presto.sql.planner.PartitioningScheme.Replication;
import com.facebook.presto.sql.planner.Symbol;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import javax.annotation.concurrent.Immutable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.facebook.presto.sql.planner.PartitioningScheme.Replication.REPLICATE_NOTHING;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.COORDINATOR_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.SOURCE_DISTRIBUTION;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.collect.Iterables.transform;
import static java.util.Objects.requireNonNull;

class ActualProperties
{
    private final Global global;
    private final List<LocalProperty<Symbol>> localProperties;
    private final Map<Symbol, NullableValue> constants;

    private ActualProperties(
            Global global,
            List<? extends LocalProperty<Symbol>> localProperties,
            Map<Symbol, NullableValue> constants)
    {
        requireNonNull(global, "globalProperties is null");
        requireNonNull(localProperties, "localProperties is null");
        requireNonNull(constants, "constants is null");

        this.global = global;

        // The constants field implies a ConstantProperty in localProperties (but not vice versa).
        // Let's make sure to include the constants into the local constant properties.
        Set<Symbol> localConstants = LocalProperties.extractLeadingConstants(localProperties);
        localProperties = LocalProperties.stripLeadingConstants(localProperties);

        Set<Symbol> updatedLocalConstants = ImmutableSet.<Symbol>builder()
                .addAll(localConstants)
                .addAll(constants.keySet())
                .build();

        List<LocalProperty<Symbol>> updatedLocalProperties = LocalProperties.normalizeAndPrune(ImmutableList.<LocalProperty<Symbol>>builder()
                .addAll(transform(updatedLocalConstants, ConstantProperty::new))
                .addAll(localProperties)
                .build());

        this.localProperties = ImmutableList.copyOf(updatedLocalProperties);
        this.constants = ImmutableMap.copyOf(constants);
    }

    public boolean isCoordinatorOnly()
    {
        return global.isCoordinatorOnly();
    }

    /**
     * @returns true if the plan will only execute on a single node
     */
    public boolean isSingleNode()
    {
        return global.isSingleNode();
    }

    public Replication getReplication()
    {
        return global.getReplication();
    }

    public boolean isStreamPartitionedOn(Collection<Symbol> columns)
    {
        return isStreamPartitionedOn(columns, REPLICATE_NOTHING);
    }

    public boolean isStreamPartitionedOn(Collection<Symbol> columns, Replication replication)
    {
        return global.isStreamPartitionedOn(columns, constants.keySet(), replication);
    }

    public boolean isNodePartitionedOn(Collection<Symbol> columns)
    {
        return isNodePartitionedOn(columns, REPLICATE_NOTHING);
    }

    public boolean isNodePartitionedOn(Collection<Symbol> columns, Replication replication)
    {
        return global.isNodePartitionedOn(columns, constants.keySet(), replication);
    }

    public boolean isNodePartitionedOn(Partitioning partitioning, Replication replication)
    {
        return global.isNodePartitionedOn(partitioning, replication);
    }

    public boolean isNodePartitionedWith(ActualProperties other, Function<Symbol, Set<Symbol>> symbolMappings)
    {
        return global.isNodePartitionedWith(
                other.global,
                symbolMappings,
                symbol -> Optional.ofNullable(constants.get(symbol)),
                symbol -> Optional.ofNullable(other.constants.get(symbol)));
    }

    /**
     * @return true if all the data will effectively land in a single stream
     */
    public boolean isEffectivelySingleStream()
    {
        return global.isEffectivelySingleStream(constants.keySet());
    }

    /**
     * @return true if repartitioning on the keys will yield some difference
     */
    public boolean isStreamRepartitionEffective(Collection<Symbol> keys)
    {
        return global.isStreamRepartitionEffective(keys, constants.keySet());
    }

    public ActualProperties translate(Function<Symbol, Optional<Symbol>> translator)
    {
        Map<Symbol, NullableValue> translatedConstants = new HashMap<>();
        for (Map.Entry<Symbol, NullableValue> entry : constants.entrySet()) {
            Optional<Symbol> translatedKey = translator.apply(entry.getKey());
            if (translatedKey.isPresent()) {
                translatedConstants.put(translatedKey.get(), entry.getValue());
            }
        }
        return builder()
                .global(global.translate(translator, symbol -> Optional.ofNullable(constants.get(symbol))))
                .local(LocalProperties.translate(localProperties, translator))
                .constants(translatedConstants)
                .build();
    }

    public Optional<Partitioning> getNodePartitioning()
    {
        return global.getNodePartitioning();
    }

    public Map<Symbol, NullableValue> getConstants()
    {
        return constants;
    }

    public List<LocalProperty<Symbol>> getLocalProperties()
    {
        return localProperties;
    }

    public ActualProperties withReplication(Replication replication)
    {
        return builderFrom(this)
                .global(global.withReplication(replication))
                .build();
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static Builder builderFrom(ActualProperties properties)
    {
        return new Builder(properties.global, properties.localProperties, properties.constants);
    }

    public static class Builder
    {
        private Global global;
        private List<LocalProperty<Symbol>> localProperties;
        private Map<Symbol, NullableValue> constants;

        public Builder(Global global, List<LocalProperty<Symbol>> localProperties, Map<Symbol, NullableValue> constants)
        {
            this.global = global;
            this.localProperties = localProperties;
            this.constants = constants;
        }

        public Builder()
        {
            this.global = Global.arbitraryPartition();
            this.localProperties = ImmutableList.of();
            this.constants = ImmutableMap.of();
        }

        public Builder global(Global global)
        {
            this.global = global;
            return this;
        }

        public Builder global(ActualProperties other)
        {
            this.global = other.global;
            return this;
        }

        public Builder local(List<? extends LocalProperty<Symbol>> localProperties)
        {
            this.localProperties = ImmutableList.copyOf(localProperties);
            return this;
        }

        public Builder constants(Map<Symbol, NullableValue> constants)
        {
            this.constants = ImmutableMap.copyOf(constants);
            return this;
        }

        public ActualProperties build()
        {
            return new ActualProperties(global, localProperties, constants);
        }
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(global, localProperties, constants.keySet());
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final ActualProperties other = (ActualProperties) obj;
        return Objects.equals(this.global, other.global)
                && Objects.equals(this.localProperties, other.localProperties)
                && Objects.equals(this.constants.keySet(), other.constants.keySet());
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("globalProperties", global)
                .add("localProperties", localProperties)
                .add("constants", constants)
                .toString();
    }

    @Immutable
    public static final class Global
    {
        // Description of the partitioning of the data across nodes
        private final Optional<Partitioning> nodePartitioning; // if missing => partitioned with some unknown scheme
        // Description of the partitioning of the data across streams (splits)
        private final Optional<Partitioning> streamPartitioning; // if missing => partitioned with some unknown scheme

        // NOTE: Partitioning on zero columns (or effectively zero columns if the columns are constant) indicates that all
        // the rows will be partitioned into a single node or stream. However, this can still be a partitioned plan in that the plan
        // will be executed on multiple servers, but only one server will get all the data.

        private final Replication replication;

        private Global(Optional<Partitioning> nodePartitioning, Optional<Partitioning> streamPartitioning, Replication replication)
        {
            this.nodePartitioning = requireNonNull(nodePartitioning, "nodePartitioning is null");
            this.streamPartitioning = requireNonNull(streamPartitioning, "streamPartitioning is null");
            this.replication = requireNonNull(replication, "replication is null");
        }

        public static Global coordinatorSingleStreamPartition()
        {
            return partitionedOn(
                    COORDINATOR_DISTRIBUTION,
                    ImmutableList.of(),
                    Optional.of(ImmutableList.of()));
        }

        public static Global singleStreamPartition()
        {
            return partitionedOn(
                    SINGLE_DISTRIBUTION,
                    ImmutableList.of(),
                    Optional.of(ImmutableList.of()));
        }

        public static Global arbitraryPartition()
        {
            return new Global(Optional.empty(), Optional.empty(), REPLICATE_NOTHING);
        }

        public static Global partitionedOn(PartitioningHandle nodePartitioningHandle, List<Symbol> nodePartitioning, Optional<List<Symbol>> streamPartitioning)
        {
            return new Global(
                    Optional.of(Partitioning.create(nodePartitioningHandle, nodePartitioning)),
                    streamPartitioning.map(columns -> Partitioning.create(SOURCE_DISTRIBUTION, columns)),
                    REPLICATE_NOTHING);
        }

        public static Global partitionedOn(Partitioning nodePartitioning, Optional<Partitioning> streamPartitioning)
        {
            return new Global(
                    Optional.of(nodePartitioning),
                    streamPartitioning,
                    REPLICATE_NOTHING);
        }

        public static Global streamPartitionedOn(List<Symbol> streamPartitioning)
        {
            return new Global(
                    Optional.empty(),
                    Optional.of(Partitioning.create(SOURCE_DISTRIBUTION, streamPartitioning)),
                    REPLICATE_NOTHING);
        }

        public Global withReplication(Replication replication)
        {
            return new Global(nodePartitioning, streamPartitioning, replication);
        }

        public Replication getReplication()
        {
            return replication;
        }

        /**
         * @returns true if the plan will only execute on a single node
         */
        private boolean isSingleNode()
        {
            if (!nodePartitioning.isPresent()) {
                return false;
            }

            return nodePartitioning.get().getHandle().isSingleNode();
        }

        private boolean isCoordinatorOnly()
        {
            if (!nodePartitioning.isPresent()) {
                return false;
            }

            return nodePartitioning.get().getHandle().isCoordinatorOnly();
        }

        private boolean isNodePartitionedOn(Collection<Symbol> columns, Set<Symbol> constants, Replication replication)
        {
            return nodePartitioning.isPresent() && nodePartitioning.get().isPartitionedOn(columns, constants) && Objects.equals(this.replication, replication);
        }

        private boolean isNodePartitionedOn(Partitioning partitioning, Replication replication)
        {
            return nodePartitioning.isPresent() && nodePartitioning.get().equals(partitioning) && Objects.equals(this.replication, replication);
        }

        private boolean isNodePartitionedWith(
                Global other,
                Function<Symbol, Set<Symbol>> symbolMappings,
                Function<Symbol, Optional<NullableValue>> leftConstantMapping,
                Function<Symbol, Optional<NullableValue>> rightConstantMapping)
        {
            return nodePartitioning.isPresent() &&
                    other.nodePartitioning.isPresent() &&
                    nodePartitioning.get().isPartitionedWith(
                            other.nodePartitioning.get(),
                            symbolMappings,
                            leftConstantMapping,
                            rightConstantMapping) &&
                    Objects.equals(replication, other.replication);
        }

        private Optional<Partitioning> getNodePartitioning()
        {
            return nodePartitioning;
        }

        private boolean isStreamPartitionedOn(Collection<Symbol> columns, Set<Symbol> constants, Replication replication)
        {
            return streamPartitioning.isPresent() && streamPartitioning.get().isPartitionedOn(columns, constants) && Objects.equals(this.replication, replication);
        }

        /**
         * @return true if all the data will effectively land in a single stream
         */
        private boolean isEffectivelySingleStream(Set<Symbol> constants)
        {
            return streamPartitioning.isPresent() && streamPartitioning.get().isEffectivelySinglePartition(constants) && replication.replicatesNothing();
        }

        /**
         * @return true if repartitioning on the keys will yield some difference
         */
        private boolean isStreamRepartitionEffective(Collection<Symbol> keys, Set<Symbol> constants)
        {
            return (!streamPartitioning.isPresent() || streamPartitioning.get().isRepartitionEffective(keys, constants)) && replication.replicatesNothing();
        }

        private Global translate(Function<Symbol, Optional<Symbol>> translator, Function<Symbol, Optional<NullableValue>> constants)
        {
            return new Global(
                    nodePartitioning.flatMap(partitioning -> partitioning.translate(translator, constants)),
                    streamPartitioning.flatMap(partitioning -> partitioning.translate(translator, constants)),
                    replication);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(nodePartitioning, streamPartitioning, replication);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final Global other = (Global) obj;
            return Objects.equals(this.nodePartitioning, other.nodePartitioning) &&
                    Objects.equals(this.streamPartitioning, other.streamPartitioning) &&
                    Objects.equals(this.replication, other.replication);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("nodePartitioning", nodePartitioning)
                    .add("streamPartitioning", streamPartitioning)
                    .add("replication", replication)
                    .toString();
        }
    }
}
