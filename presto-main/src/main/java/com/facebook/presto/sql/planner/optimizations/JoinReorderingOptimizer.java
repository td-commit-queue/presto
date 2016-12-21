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
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.PlanNodeCost;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolAllocator;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.SimplePlanRewriter;

import java.util.List;
import java.util.Map;

import static com.facebook.presto.util.ImmutableCollectors.toImmutableList;
import static java.util.Objects.requireNonNull;

public class JoinReorderingOptimizer
        implements PlanOptimizer
{
    private final CostCalculator costCalculator;

    public JoinReorderingOptimizer(CostCalculator costCalculator)
    {
        this.costCalculator = requireNonNull(costCalculator, "costCalculator can not be null");
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, Map<Symbol, Type> types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(types, "types is null");

        if (SystemSessionProperties.isJoinReorderingEnabled(session)) {
            return SimplePlanRewriter.rewriteWith(new Rewriter(session), plan);
        }
        else {
            return plan;
        }
    }

    private class Rewriter
            extends SimplePlanRewriter<Void>
    {
        private final Session session;

        public Rewriter(Session session)
        {
            this.session = session;
        }

        @Override
        public PlanNode visitJoin(JoinNode node, RewriteContext<Void> context)
        {
            PlanNode leftRewritten = context.defaultRewrite(node.getLeft());
            PlanNode rightRewritten = context.defaultRewrite(node.getRight());

            PlanNodeCost leftCost = costCalculator.calculateCostForNode(session, leftRewritten);
            PlanNodeCost rightCost = costCalculator.calculateCostForNode(session, rightRewritten);

            boolean leftSizeKnown = !leftCost.getOutputSizeInBytes().isValueUnknown();
            boolean rightSizeKnown = !rightCost.getOutputSizeInBytes().isValueUnknown();
            double leftSize = leftCost.getOutputSizeInBytes().getValue();
            double rightSize = rightCost.getOutputSizeInBytes().getValue();

            boolean leftCountKnown = !leftCost.getOutputRowCount().isValueUnknown();
            boolean rightCountKnown = !rightCost.getOutputRowCount().isValueUnknown();
            double leftCount = leftCost.getOutputRowCount().getValue();
            double rightCount = rightCost.getOutputRowCount().getValue();

            boolean flipNeeded = false;
            if (leftSizeKnown && rightSizeKnown) {
                if (leftSize < rightSize) {
                    flipNeeded = true;
                }
            }
            else if (leftCountKnown && rightCountKnown && leftCount < rightCount) {
                flipNeeded = true;
            }

            if (flipNeeded) {
                return new JoinNode(node.getId(),
                        flipJoinType(node.getType()),
                        rightRewritten,
                        leftRewritten,
                        flipJoinCriteria(node.getCriteria()),
                        node.getOutputSymbols(),
                        node.getFilter(),
                        node.getRightHashSymbol(),
                        node.getLeftHashSymbol());
            }

            if (leftRewritten != node.getLeft() || rightRewritten != node.getRight()) {
                return new JoinNode(node.getId(),
                        node.getType(),
                        leftRewritten,
                        rightRewritten,
                        node.getCriteria(),
                        node.getOutputSymbols(),
                        node.getFilter(),
                        node.getLeftHashSymbol(),
                        node.getRightHashSymbol());
            }

            return node;
        }

        private JoinNode.Type flipJoinType(JoinNode.Type joinType)
        {
            switch (joinType) {
                case LEFT:
                    return JoinNode.Type.RIGHT;
                case RIGHT:
                    return JoinNode.Type.LEFT;
                case INNER:
                case FULL:
                    return joinType;
                default:
                    throw new IllegalArgumentException("unknown joinType: " + joinType);
            }
        }

        private List<JoinNode.EquiJoinClause> flipJoinCriteria(List<JoinNode.EquiJoinClause> criteria)
        {
            return criteria.stream()
                    .map(clause -> new JoinNode.EquiJoinClause(clause.getRight(), clause.getLeft()))
                    .collect(toImmutableList());
        }
    }
}
