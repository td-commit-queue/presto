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

import com.facebook.presto.Session;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.cost.CoefficientBasedCostCalculator;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.assertions.PlanAssert;
import com.facebook.presto.sql.planner.assertions.PlanMatchPattern;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.testing.LocalQueryRunner;
import com.facebook.presto.tpch.TpchConnectorFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.anyTree;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.equiJoinClause;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.join;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.tableScan;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;

public class TestJoinReorderorderingOptimizer
{
    private final LocalQueryRunner queryRunner;
    private final Session reorderingEnabledSession;
    private final Session reorderingDisabledSession;
    private final CostCalculator costCalculator;

    public TestJoinReorderorderingOptimizer()
    {
        this.queryRunner = new LocalQueryRunner(testSessionBuilder()
                .setCatalog("local")
                .setSchema("tiny")
                .build());

        reorderingEnabledSession = Session.builder(queryRunner.getDefaultSession())
                .setSystemProperty(SystemSessionProperties.REORDER_JOINS, "true")
                .build();
        reorderingDisabledSession = Session.builder(queryRunner.getDefaultSession())
                .setSystemProperty(SystemSessionProperties.REORDER_JOINS, "false")
                .build();

        queryRunner.createCatalog(queryRunner.getDefaultSession().getCatalog().get(),
                new TpchConnectorFactory(1),
                ImmutableMap.<String, String>of());
        costCalculator = new CoefficientBasedCostCalculator(queryRunner.getMetadata());
    }

    @Test
    public void testNoReorderNeeded()
    {
        @Language("SQL") String sql = "select * from nation join region on nation.regionkey = region.regionkey";

        PlanMatchPattern pattern =
                anyTree(
                        join(JoinNode.Type.INNER,
                                ImmutableList.of(equiJoinClause("X", "Y")),
                                anyTree(tableScan("nation")),
                                anyTree(tableScan("region"))));

        assertPlan(reorderingEnabledSession, sql, pattern);
    }

    @Test
    public void testReorderNeeded()
    {
        @Language("SQL") String sql = "select * from region join nation on nation.regionkey = region.regionkey";

        PlanMatchPattern pattern =
                anyTree(
                        join(JoinNode.Type.INNER,
                                ImmutableList.of(equiJoinClause("X", "Y")),
                                anyTree(tableScan("nation")),
                                anyTree(tableScan("region"))));

        assertPlan(reorderingEnabledSession, sql, pattern);
    }

    @Test
    public void testReorderNeededButDisabled()
    {
        @Language("SQL") String sql = "select * from region join nation on nation.regionkey = region.regionkey";

        PlanMatchPattern pattern =
                anyTree(
                        join(JoinNode.Type.INNER,
                                ImmutableList.of(equiJoinClause("X", "Y")),
                                anyTree(tableScan("region")),
                                anyTree(tableScan("nation"))));

        assertPlan(reorderingDisabledSession, sql, pattern);
    }

    private void assertPlan(Session session, @Language("SQL") String sql, PlanMatchPattern pattern)
    {
        queryRunner.inTransaction(session, transactionSession -> {
            Plan actualPlan = queryRunner.createPlan(transactionSession, sql);
            PlanAssert.assertPlan(transactionSession, queryRunner.getMetadata(), costCalculator, actualPlan, pattern);
            return null;
        });
    }
}
