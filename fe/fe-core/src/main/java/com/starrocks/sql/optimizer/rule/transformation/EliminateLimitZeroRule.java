// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.collect.Lists;
import com.starrocks.sql.optimizer.ExpressionContext;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalLimitOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalValuesOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class EliminateLimitZeroRule extends TransformationRule {
    public EliminateLimitZeroRule() {
        super(RuleType.TF_ELIMINATE_LIMIT_ZERO, Pattern.create(OperatorType.LOGICAL_LIMIT)
                .addChildren(Pattern.create(OperatorType.PATTERN_LEAF)));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        LogicalLimitOperator limit = (LogicalLimitOperator) input.getOp();
        return limit.getLimit() == 0;
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        ColumnRefSet outputColumnIds =
                ((LogicalLimitOperator) input.getOp()).getOutputColumns(new ExpressionContext(input));

        List<ColumnRefOperator> outputColumns = outputColumnIds.getStream().mapToObj(
                id -> context.getColumnRefFactory().getColumnRef(id)).collect(Collectors.toList());
        LogicalValuesOperator emptyOperator = new LogicalValuesOperator(
                outputColumns,
                Collections.emptyList());
        return Lists.newArrayList(OptExpression.create(emptyOperator));
    }
}
