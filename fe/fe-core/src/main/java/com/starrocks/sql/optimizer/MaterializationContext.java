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


package com.starrocks.sql.optimizer;

import com.starrocks.catalog.MaterializedView;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;

import java.util.Map;
import java.util.Set;

public class MaterializationContext {
    private MaterializedView mv;
    // scan materialized view operator
    private Operator scanMvOperator;
    // logical OptExpression for query of materialized view
    private OptExpression mvExpression;

    private ColumnRefFactory mvColumnRefFactory;

    // logical OptExpression for query
    private OptExpression queryExpression;

    private ColumnRefFactory queryRefFactory;

    private OptimizerContext optimizerContext;

    private Map<ColumnRefOperator, ColumnRefOperator> outputMapping;

    private Set<String> mvPartitionNamesToRefresh;

    public MaterializationContext(MaterializedView mv,
                                  OptExpression mvExpression,
                                  ColumnRefFactory queryColumnRefFactory,
                                  ColumnRefFactory mvColumnRefFactory,
                                  Set<String> mvPartitionNamesToRefresh) {
        this.mv = mv;
        this.mvExpression = mvExpression;
        this.queryRefFactory = queryColumnRefFactory;
        this.mvColumnRefFactory = mvColumnRefFactory;
        this.mvPartitionNamesToRefresh = mvPartitionNamesToRefresh;
    }

    public MaterializedView getMv() {
        return mv;
    }

    public Operator getScanMvOperator() {
        return scanMvOperator;
    }

    public void setScanMvOperator(Operator scanMvOperator) {
        this.scanMvOperator = scanMvOperator;
    }

    public OptExpression getMvExpression() {
        return mvExpression;
    }

    public ColumnRefFactory getMvColumnRefFactory() {
        return mvColumnRefFactory;
    }

    public OptExpression getQueryExpression() {
        return queryExpression;
    }

    public void setQueryExpression(OptExpression queryExpression) {
        this.queryExpression = queryExpression;
    }

    public ColumnRefFactory getQueryRefFactory() {
        return queryRefFactory;
    }

    public void setQueryRefFactory(ColumnRefFactory queryRefFactory) {
        this.queryRefFactory = queryRefFactory;
    }

    public OptimizerContext getOptimizerContext() {
        return optimizerContext;
    }

    public void setOptimizerContext(OptimizerContext optimizerContext) {
        this.optimizerContext = optimizerContext;
    }

    public Map<ColumnRefOperator, ColumnRefOperator> getOutputMapping() {
        return outputMapping;
    }

    public void setOutputMapping(Map<ColumnRefOperator, ColumnRefOperator> outputMapping) {
        this.outputMapping = outputMapping;
    }

    public Set<String> getMvPartitionNamesToRefresh() {
        return mvPartitionNamesToRefresh;
    }
}
