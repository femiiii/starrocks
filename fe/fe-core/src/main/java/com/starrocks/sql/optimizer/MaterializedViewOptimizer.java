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

import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.ExpressionRangePartitionInfo;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.RangePartitionInfo;
import com.starrocks.catalog.Table;
import com.starrocks.common.Pair;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.operator.logical.LogicalScanOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MvUtils;
import com.starrocks.sql.optimizer.transformer.LogicalPlan;

import java.util.List;
import java.util.Set;

public class MaterializedViewOptimizer {
    private List<ColumnRefOperator> outputExpressions;

    public OptExpression optimize(MaterializedView mv,
                                  ColumnRefFactory columnRefFactory,
                                  ConnectContext connectContext,
                                  Set<String> mvPartitionNamesToRefresh) {
        String mvSql = mv.getViewDefineSql();
        Pair<OptExpression, LogicalPlan> plans = MvUtils.getRuleOptimizedLogicalPlan(mvSql, columnRefFactory, connectContext);
        if (plans == null) {
            return null;
        }
        outputExpressions = plans.second.getOutputColumn();
        OptExpression mvPlan = plans.first;
        if (mv.getPartitionInfo() instanceof ExpressionRangePartitionInfo && !mvPartitionNamesToRefresh.isEmpty()) {
            boolean ret = updateScanWithPartitionRange(mv, mvPlan, mvPartitionNamesToRefresh);
            if (!ret) {
                return null;
            }
        }
        return mvPlan;
    }

    public List<ColumnRefOperator> getOutputExpressions() {
        return outputExpressions;
    }

    // try to get partitial partition predicate of partitioned mv.
    // for example, mv1 has two partition: p1:[2022-01-01, 2022-01-02), p2:[2022-01-02, 2022-01-03).
    // p1 is updated, p2 is outdated.
    // mv1's base partition table is t1, partition column is k1.
    // then this function will add predicate: k1 >= "2022-01-01" and k1 < "2022-01-02" to scan node of t1
    public boolean updateScanWithPartitionRange(MaterializedView mv,
                                                OptExpression mvPlan,
                                                Set<String> mvPartitionNamesToRefresh) {
        Pair<Table, Column> partitionTableAndColumns = mv.getPartitionTableAndColumn();
        if (partitionTableAndColumns == null) {
            return false;
        }
        if (!partitionTableAndColumns.first.isNativeTable()) {
            // for external table, we can not get modified partitions now.
            return false;
        }
        OlapTable partitionByTable = (OlapTable) partitionTableAndColumns.first;
        List<Range<PartitionKey>> latestBaseTableRanges =
                getLatestPartitionRangeForTable(partitionByTable, mv, mvPartitionNamesToRefresh);
        if (latestBaseTableRanges.isEmpty()) {
            // if do not have an uptodate partition, do not rewrite
            return false;
        }

        Column partitionColumn = partitionTableAndColumns.second;
        List<OptExpression> scanExprs = MvUtils.collectScanExprs(mvPlan);
        for (OptExpression scanExpr : scanExprs) {
            LogicalScanOperator scanOperator = (LogicalScanOperator) scanExpr.getOp();
            Table scanTable = scanOperator.getTable();
            if ((scanTable.isLocalTable() && !scanTable.equals(partitionTableAndColumns.first))
                    || (!scanTable.isLocalTable()) && !scanTable.getTableIdentifier().equals(
                    partitionTableAndColumns.first.getTableIdentifier())) {
                continue;
            }
            ColumnRefOperator columnRef = scanOperator.getColumnReference(partitionColumn);
            List<ScalarOperator> partitionPredicates = MvUtils.convertRanges(columnRef, latestBaseTableRanges);
            ScalarOperator partialPartitionPredicate = Utils.compoundOr(partitionPredicates);
            ScalarOperator originalPredicate = scanOperator.getPredicate();
            ScalarOperator newPredicate = Utils.compoundAnd(originalPredicate, partialPartitionPredicate);
            scanOperator.setPredicate(newPredicate);
        }
        return true;
    }

    private List<Range<PartitionKey>> getLatestPartitionRangeForTable(OlapTable partitionByTable,
                                                                      MaterializedView mv,
                                                                      Set<String> mvPartitionNamesToRefresh) {
        Set<String> modifiedPartitionNames = mv.getUpdatedPartitionNamesOfTable(partitionByTable);
        List<Range<PartitionKey>> baseTableRanges = getLatestPartitionRange(partitionByTable, modifiedPartitionNames);
        List<Range<PartitionKey>> mvRanges = getLatestPartitionRange(mv, mvPartitionNamesToRefresh);
        List<Range<PartitionKey>> latestBaseTableRanges = Lists.newArrayList();
        for (Range<PartitionKey> range : baseTableRanges) {
            if (mvRanges.stream().anyMatch(mvRange -> mvRange.encloses(range))) {
                latestBaseTableRanges.add(range);
            }
        }
        latestBaseTableRanges = MvUtils.mergeRanges(latestBaseTableRanges);
        return latestBaseTableRanges;
    }

    private List<Range<PartitionKey>> getLatestPartitionRange(OlapTable table, Set<String> modifiedPartitionNames) {
        // partitions that will be excluded
        Set<Long> filteredIds = Sets.newHashSet();
        for (Partition p : table.getPartitions()) {
            if (modifiedPartitionNames.contains(p.getName()) || !p.hasData()) {
                filteredIds.add(p.getId());
            }
        }
        RangePartitionInfo rangePartitionInfo = (RangePartitionInfo) table.getPartitionInfo();
        List<Range<PartitionKey>> latestPartitionRanges = rangePartitionInfo.getRangeList(filteredIds, false);
        return latestPartitionRanges;
    }
}
