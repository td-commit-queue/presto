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
package com.facebook.presto.tpch;

import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorNodePartitioning;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ConnectorTableLayoutResult;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.LocalProperty;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.spi.SortingProperty;
import com.facebook.presto.spi.block.SortOrder;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.statistics.Estimate;
import com.facebook.presto.spi.statistics.TableStatistics;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.DateType;
import com.facebook.presto.spi.type.DoubleType;
import com.facebook.presto.spi.type.IntegerType;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.tpch.LineItemColumn;
import io.airlift.tpch.OrderColumn;
import io.airlift.tpch.OrderGenerator;
import io.airlift.tpch.TpchColumn;
import io.airlift.tpch.TpchColumnType;
import io.airlift.tpch.TpchEntity;
import io.airlift.tpch.TpchTable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DecimalType.createDecimalType;
import static com.facebook.presto.spi.type.StandardTypes.DECIMAL;
import static com.facebook.presto.spi.type.VarcharType.createVarcharType;
import static com.facebook.presto.tpch.Types.checkType;
import static java.util.Objects.requireNonNull;

public class TpchMetadata
        implements ConnectorMetadata
{
    public static final int TPCH_GENERATOR_SCALE = 2;

    public static final String TINY_SCHEMA_NAME = "tiny";
    public static final String TINY_DECIMAL_SCHEMA_NAME = "tiny_decimal";
    public static final double TINY_SCALE_FACTOR = 0.01;
    public static final String ROW_NUMBER_COLUMN_NAME = "row_number";

    private static final Pattern SCHEMA_PATTERN = Pattern.compile("\\bsf([0-9]*\\.?[0-9]+)(_(" + DECIMAL + "))?\\b");
    private static final int SCALE_FACTOR_GROUP = 1;
    private static final int NUMERIC_TYPE_GROUP = 3;

    private static final Type DECIMAL_TYPE = createDecimalType(12, TPCH_GENERATOR_SCALE);

    private final String connectorId;
    private final Set<String> tableNames;

    public TpchMetadata(String connectorId)
    {
        ImmutableSet.Builder<String> tableNames = ImmutableSet.builder();
        for (TpchTable<?> tpchTable : TpchTable.getTables()) {
            tableNames.add(tpchTable.getTableName());
        }
        this.tableNames = tableNames.build();
        this.connectorId = connectorId;
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return listSchemaNames();
    }

    @Override
    public TpchTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        requireNonNull(tableName, "tableName is null");
        if (!tableNames.contains(tableName.getTableName())) {
            return null;
        }

        Optional<SchemaParameters> schemaParameters = extractSchemaParameters(tableName.getSchemaName());
        if (!schemaParameters.isPresent()) {
            return null;
        }

        return new TpchTableHandle(connectorId, tableName.getTableName(), schemaParameters.get().scaleFactor, tableName.getSchemaName(), schemaParameters.get().useDecimalNumericType);
    }

    @Override
    public List<ConnectorTableLayoutResult> getTableLayouts(
            ConnectorSession session,
            ConnectorTableHandle table,
            Constraint<ColumnHandle> constraint,
            Optional<Set<ColumnHandle>> desiredColumns)
    {
        TpchTableHandle tableHandle = checkType(table, TpchTableHandle.class, "table");

        Optional<ConnectorNodePartitioning> nodePartition = Optional.empty();
        Optional<Set<ColumnHandle>> partitioningColumns = Optional.empty();
        List<LocalProperty<ColumnHandle>> localProperties = ImmutableList.of();

        Map<String, ColumnHandle> columns = getColumnHandles(session, tableHandle);
        if (tableHandle.getTableName().equals(TpchTable.ORDERS.getTableName())) {
            ColumnHandle orderKeyColumn = columns.get(OrderColumn.ORDER_KEY.getColumnName());
            nodePartition = Optional.of(new ConnectorNodePartitioning(
                    new TpchPartitioningHandle(
                            TpchTable.ORDERS.getTableName(),
                            calculateTotalRows(OrderGenerator.SCALE_BASE, tableHandle.getScaleFactor())),
                    ImmutableList.of(orderKeyColumn)));
            partitioningColumns = Optional.of(ImmutableSet.of(orderKeyColumn));
            localProperties = ImmutableList.of(new SortingProperty<>(orderKeyColumn, SortOrder.ASC_NULLS_FIRST));
        }
        else if (tableHandle.getTableName().equals(TpchTable.LINE_ITEM.getTableName())) {
            ColumnHandle orderKeyColumn = columns.get(OrderColumn.ORDER_KEY.getColumnName());
            nodePartition = Optional.of(new ConnectorNodePartitioning(
                    new TpchPartitioningHandle(
                            TpchTable.ORDERS.getTableName(),
                            calculateTotalRows(OrderGenerator.SCALE_BASE, tableHandle.getScaleFactor())),
                    ImmutableList.of(orderKeyColumn)));
            partitioningColumns = Optional.of(ImmutableSet.of(orderKeyColumn));
            localProperties = ImmutableList.of(
                    new SortingProperty<>(orderKeyColumn, SortOrder.ASC_NULLS_FIRST),
                    new SortingProperty<>(columns.get(LineItemColumn.LINE_NUMBER.getColumnName()), SortOrder.ASC_NULLS_FIRST));
        }

        ConnectorTableLayout layout = new ConnectorTableLayout(
                new TpchTableLayoutHandle(tableHandle),
                Optional.empty(),
                TupleDomain.all(), // TODO: return well-known properties (e.g., orderkey > 0, etc)
                nodePartition,
                partitioningColumns,
                Optional.empty(),
                localProperties);

        return ImmutableList.of(new ConnectorTableLayoutResult(layout, constraint.getSummary()));
    }

    @Override
    public ConnectorTableLayout getTableLayout(ConnectorSession session, ConnectorTableLayoutHandle handle)
    {
        TpchTableLayoutHandle layout = checkType(handle, TpchTableLayoutHandle.class, "layout");

        // tables in this connector have a single layout
        return getTableLayouts(session, layout.getTable(), Constraint.alwaysTrue(), Optional.empty())
                .get(0)
                .getTableLayout();
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        TpchTableHandle tpchTableHandle = checkType(tableHandle, TpchTableHandle.class, "tableHandle");

        TpchTable<?> tpchTable = TpchTable.getTable(tpchTableHandle.getTableName());
        String schemaName = schemaNameForTpchTableHandle(tpchTableHandle);

        return getTableMetadata(schemaName, tpchTable, tpchTableHandle.isUseDecimalNumericType());
    }

    private static ConnectorTableMetadata getTableMetadata(String schemaName, TpchTable<?> tpchTable, boolean useDecimalNumericType)
    {
        ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
        for (TpchColumn<? extends TpchEntity> column : tpchTable.getColumns()) {
            columns.add(new ColumnMetadata(column.getColumnName(), getPrestoType(column.getType(), useDecimalNumericType)));
        }
        columns.add(new ColumnMetadata(ROW_NUMBER_COLUMN_NAME, BIGINT, null, true));

        SchemaTableName tableName = new SchemaTableName(schemaName, tpchTable.getTableName());
        return new ConnectorTableMetadata(tableName, columns.build());
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        ImmutableMap.Builder<String, ColumnHandle> builder = ImmutableMap.builder();
        for (ColumnMetadata columnMetadata : getTableMetadata(session, tableHandle).getColumns()) {
            builder.put(columnMetadata.getName(), new TpchColumnHandle(columnMetadata.getName(), columnMetadata.getType()));
        }
        return builder.build();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> tableColumns = ImmutableMap.builder();
        for (String schemaName : getSchemaNames(prefix.getSchemaName())) {
            for (TpchTable<?> tpchTable : TpchTable.getTables()) {
                if (prefix.getTableName() == null || tpchTable.getTableName().equals(prefix.getTableName())) {
                    Optional<SchemaParameters> schemaParameters = extractSchemaParameters(schemaName);
                    ConnectorTableMetadata tableMetadata = getTableMetadata(schemaName, tpchTable, schemaParameters.get().useDecimalNumericType);
                    tableColumns.put(new SchemaTableName(schemaName, tpchTable.getTableName()), tableMetadata.getColumns());
                }
            }
        }
        return tableColumns.build();
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle, Constraint<ColumnHandle> constraint)
    {
        TpchTableHandle table = checkType(tableHandle, TpchTableHandle.class, "tableHandle");
        return new TableStatistics(new Estimate(getRowCount(table)), ImmutableMap.of());
    }

    public long getRowCount(TpchTableHandle tpchTableHandle)
    {
        // todo expose row counts from airlift-tpch instead of hardcoding it here
        // todo add stats for columns
        String tableName = tpchTableHandle.getTableName();
        double scaleFactor = tpchTableHandle.getScaleFactor();
        switch (tableName) {
            case "customer":
                return (long) (150_000 * scaleFactor);
            case "orders":
                return (long) (1_500_000 * scaleFactor);
            case "lineitem":
                return (long) (6_000_000 * scaleFactor);
            case "part":
                return (long) (200_000 * scaleFactor);
            case "partsupp":
                return (long) (800_000 * scaleFactor);
            case "supplier":
                return (long) (10_000 * scaleFactor);
            case "nation":
                return 25;
            case "region":
                return 5;
            default:
                throw new IllegalArgumentException("unknown tpch table name '" + tableName + "'");
        }
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        ConnectorTableMetadata tableMetadata = getTableMetadata(session, tableHandle);
        String columnName = checkType(columnHandle, TpchColumnHandle.class, "columnHandle").getColumnName();

        for (ColumnMetadata column : tableMetadata.getColumns()) {
            if (column.getName().equals(columnName)) {
                return column;
            }
        }
        throw new IllegalArgumentException(String.format("Table %s does not have column %s", tableMetadata.getTable(), columnName));
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, String schemaNameOrNull)
    {
        ImmutableList.Builder<SchemaTableName> builder = ImmutableList.builder();
        for (String schemaName : getSchemaNames(schemaNameOrNull)) {
            for (TpchTable<?> tpchTable : TpchTable.getTables()) {
                builder.add(new SchemaTableName(schemaName, tpchTable.getTableName()));
            }
        }
        return builder.build();
    }

    private List<String> getSchemaNames(String schemaNameOrNull)
    {
        List<String> schemaNames;
        if (schemaNameOrNull == null) {
            schemaNames = listSchemaNames();
        }
        else if (extractSchemaParameters(schemaNameOrNull).isPresent()) {
            schemaNames = ImmutableList.of(schemaNameOrNull);
        }
        else {
            schemaNames = ImmutableList.of();
        }
        return schemaNames;
    }

    private static String schemaNameForTpchTableHandle(TpchTableHandle tpchTableHandle)
    {
        return tpchTableHandle.getSchemaName();
    }

    private static Optional<SchemaParameters> extractSchemaParameters(String schemaName)
    {
        if (TINY_SCHEMA_NAME.equals(schemaName)) {
            return Optional.of(new SchemaParameters(TINY_SCALE_FACTOR, false));
        }
        else if (TINY_DECIMAL_SCHEMA_NAME.equals(schemaName)) {
            return Optional.of(new SchemaParameters(TINY_SCALE_FACTOR, true));
        }

        Matcher match = SCHEMA_PATTERN.matcher(schemaName);
        if (!match.matches()) {
            return Optional.empty();
        }

        double scaleFactor = Double.parseDouble(match.group(SCALE_FACTOR_GROUP));
        String numericType = match.group(NUMERIC_TYPE_GROUP);
        if (numericType == null) {
            return Optional.of(new SchemaParameters(scaleFactor, false));
        }
        else if (numericType.equals(DECIMAL)) {
            return Optional.of(new SchemaParameters(scaleFactor, true));
        }
        else {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    public static List<String> listSchemaNames()
    {
        List<String> scaleFactors = ImmutableList.of("1", "100", "300", "1000", "3000", "10000", "30000", "100000");
        List<String> numericTypes = ImmutableList.of(DECIMAL);

        ImmutableList.Builder<String> schemaNames = ImmutableList.builder();

        schemaNames.add(TINY_SCHEMA_NAME);
        schemaNames.add(TINY_DECIMAL_SCHEMA_NAME);

        for (String scaleFactor : scaleFactors) {
            for (String numericType : numericTypes) {
                schemaNames.add("sf" + scaleFactor + "_" + numericType);
            }
            schemaNames.add("sf" + scaleFactor);
        }

        return schemaNames.build();
    }

    public static Type getPrestoType(TpchColumnType tpchType, boolean useDecimalNumericType)
    {
        switch (tpchType.getBase()) {
            case IDENTIFIER:
                return BigintType.BIGINT;
            case INTEGER:
                return IntegerType.INTEGER;
            case DATE:
                return DateType.DATE;
            case DOUBLE:
                if (useDecimalNumericType) {
                    return DECIMAL_TYPE;
                }
                else {
                    return DoubleType.DOUBLE;
                }
            case VARCHAR:
                return createVarcharType((int) (long) tpchType.getPrecision().get());
        }
        throw new IllegalArgumentException("Unsupported type " + tpchType);
    }

    private long calculateTotalRows(int scaleBase, double scaleFactor)
    {
        double totalRows = scaleBase * scaleFactor;
        if (totalRows > Long.MAX_VALUE) {
            throw new IllegalArgumentException("Total rows is larger than 2^64");
        }
        return (long) totalRows;
    }

    private static class SchemaParameters
    {
        final double scaleFactor;
        final boolean useDecimalNumericType;

        SchemaParameters(double scaleFactor, boolean useDecimalNumericType)
        {
            this.scaleFactor = scaleFactor;
            this.useDecimalNumericType = useDecimalNumericType;
        }
    }
}
