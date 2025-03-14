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


package com.starrocks.sql.plan;

import com.starrocks.common.FeConstants;
import com.starrocks.sql.analyzer.SemanticException;
import org.junit.Assert;
import org.junit.Test;

public class WindowTest extends PlanTestBase {

    @Test
    public void testLagWindowFunction() throws Exception {
        String sql = "select lag(id_datetime, 1, '2020-01-01') over(partition by t1c) from test_all_type;";
        String plan = getThriftPlan(sql);
        assertContains(plan, "signature:lag(DATETIME, BIGINT, DATETIME)");

        sql = "select lag(id_decimal, 1, 10000) over(partition by t1c) from test_all_type;";
        plan = getThriftPlan(sql);
        String expectSlice = "fn:TFunction(name:TFunctionName(function_name:lag), binary_type:BUILTIN," +
                " arg_types:[TTypeDesc(types:[TTypeNode(type:SCALAR, scalar_type:TScalarType(type:DECIMAL64," +
                " precision:10, scale:2))])], ret_type:TTypeDesc(types:[TTypeNode(type:SCALAR, " +
                "scalar_type:TScalarType(type:DECIMAL64, precision:10, scale:2))]), has_var_args:false, " +
                "signature:lag(DECIMAL64(10,2))";
        Assert.assertTrue(plan.contains(expectSlice));

        sql = "select lag(null, 1,1) OVER () from t0";
        plan = getFragmentPlan(sql);
        assertContains(plan, "functions: [, lag(NULL, 1, 1), ]");

        sql = "select lag(id_datetime, 1, '2020-01-01xxx') over(partition by t1c) from test_all_type;";
        expectedEx.expect(SemanticException.class);
        expectedEx.expectMessage("The third parameter of `lag` can't convert to DATETIME");
        getThriftPlan(sql);
    }

    @Test
    public void testPruneWindowColumn() throws Exception {
        String sql = "select sum(t1c) from (select t1c, lag(id_datetime, 1, '2020-01-01') over( partition by t1c)" +
                "from test_all_type) a ;";
        String plan = getFragmentPlan(sql);
        assertNotContains(plan, "ANALYTIC");
    }

    @Test
    public void testWindowFunctionTest() throws Exception {
        String sql = "select sum(id_decimal - ifnull(id_decimal, 0)) over (partition by t1c) from test_all_type";
        String plan = getThriftPlan(sql);
        Assert.assertTrue(
                plan.contains("decimal_literal:TDecimalLiteral(value:0, integer_value:00 00 00 00 00 00 00 00)"));
    }

    @Test
    public void testSameWindowFunctionReuse() throws Exception {
        String sql = "select sum(v1) over() as c1, sum(v1) over() as c2 from t0";
        String plan = getFragmentPlan(sql);
        assertContains(plan, "  3:Project\n" +
                "  |  <slot 4> : 4: sum(1: v1)\n" +
                "  |  \n" +
                "  2:ANALYTIC\n" +
                "  |  functions: [, sum(1: v1), ]");

        sql = "select sum(v1) over(order by v2 rows between 1 preceding and 1 following) as sum_v1_1," +
                " sum(v1) over(order by v2 rows between 1 preceding and 1 following) as sum_v1_2 from t0;";
        plan = getFragmentPlan(sql);
        assertContains(plan, "  4:Project\n" +
                "  |  <slot 4> : 4: sum(1: v1)\n" +
                "  |  \n" +
                "  3:ANALYTIC\n" +
                "  |  functions: [, sum(1: v1), ]");

        sql = "select c1+1, c2+2 from (select sum(v1) over() as c1, sum(v1) over() as c2 from t0) t";
        plan = getFragmentPlan(sql);
        assertContains(plan, "  3:Project\n" +
                "  |  <slot 5> : 4: sum(1: v1) + 1\n" +
                "  |  <slot 6> : 4: sum(1: v1) + 2\n" +
                "  |  \n" +
                "  2:ANALYTIC\n" +
                "  |  functions: [, sum(1: v1), ]");

        sql = "select c1+1, c2+2 from (select sum(v1) over() as c1, sum(v3) over() as c2 from t0) t";
        plan = getFragmentPlan(sql);
        assertContains(plan, "  3:Project\n" +
                "  |  <slot 6> : 4: sum(1: v1) + 1\n" +
                "  |  <slot 7> : 5: sum(3: v3) + 2\n" +
                "  |  \n" +
                "  2:ANALYTIC\n" +
                "  |  functions: [, sum(1: v1), ], [, sum(3: v3), ]");
    }

    @Test
    public void testLeadAndLagFunction() {
        String sql = "select LAG(k7, 3, 3) OVER () from baseall";
        starRocksAssert.query(sql).analysisError("The third parameter of `lag` can't convert");

        sql = "select lead(k7, 3, 3) OVER () from baseall";
        starRocksAssert.query(sql).analysisError("The third parameter of `lead` can't convert");

        sql = "select lead(k3, 3, 'kks') OVER () from baseall";
        starRocksAssert.query(sql)
                .analysisError("Convert type error in offset fn(default value); old_type=VARCHAR new_type=INT");

        sql = "select lead(id2, 1, 1) OVER () from bitmap_table";
        starRocksAssert.query(sql).analysisError("No matching function with signature: lead(bitmap,");

        sql = "select lag(id2, 1, 1) OVER () from hll_table";
        starRocksAssert.query(sql).analysisError("No matching function with signature: lag(hll,");
    }

    @Test
    public void testLeadAndLagWithBitmapAndHll() throws Exception {
        String sql = "select lead(id2, 1, bitmap_empty()) OVER () from bitmap_table";
        String plan = getFragmentPlan(sql);
        assertContains(plan, "lead(2: id2, 1, bitmap_empty())");

        sql = "select lead(id2, 1, null) OVER () from bitmap_table";
        plan = getFragmentPlan(sql);
        assertContains(plan, "lead(2: id2, 1, null)");

        sql = "select lag(id2, 1, bitmap_empty()) OVER () from bitmap_table";
        plan = getFragmentPlan(sql);
        assertContains(plan, "lag(2: id2, 1, bitmap_empty())");

        sql = "select lag(id2, 1, null) OVER () from bitmap_table";
        plan = getFragmentPlan(sql);
        assertContains(plan, "lag(2: id2, 1, null)");

        sql = "select lead(id2, 1, hll_empty()) OVER () from hll_table";
        plan = getFragmentPlan(sql);
        assertContains(plan, "lead(2: id2, 1, hll_empty())");

        sql = "select lead(id2, 1, null) OVER () from hll_table";
        plan = getFragmentPlan(sql);
        assertContains(plan, "lead(2: id2, 1, null)");

        sql = "select lag(id2, 1, hll_empty()) OVER () from hll_table";
        plan = getFragmentPlan(sql);
        assertContains(plan, "lag(2: id2, 1, hll_empty())");

        sql = "select lag(id2, 1, null) OVER () from hll_table";
        plan = getFragmentPlan(sql);
        assertContains(plan, "lag(2: id2, 1, null)");
    }

    @Test
    public void testWindowWithAgg() throws Exception {
        String sql = "SELECT v1, sum(v2),  sum(v2) over (ORDER BY v1) AS `rank` FROM t0 group BY v1, v2";
        String plan = getFragmentPlan(sql);
        assertContains(plan, "window: RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW");

        sql =
                "SELECT v1, sum(v2),  sum(v2) over (ORDER BY CASE WHEN v1 THEN 1 END DESC) AS `rank`  FROM t0 group BY v1, v2";
        plan = getFragmentPlan(sql);
        assertContains(plan, "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW");
    }

    @Test
    public void testWindowWithChildProjectAgg() throws Exception {
        String sql = "SELECT v1, sum(v2) as x1, row_number() over (ORDER BY CASE WHEN v1 THEN 1 END DESC) AS `rank` " +
                "FROM t0 group BY v1";
        String plan = getFragmentPlan(sql);
        assertContains(plan, "  2:Project\n" +
                "  |  <slot 1> : 1: v1\n" +
                "  |  <slot 4> : 4: sum\n" +
                "  |  <slot 8> : if(CAST(1: v1 AS BOOLEAN), 1, NULL)");
    }

    @Test
    public void testWindowPartitionAndSortSameColumn() throws Exception {
        String sql = "SELECT k3, avg(k3) OVER (partition by k3 order by k3) AS sum FROM baseall;";
        String plan = getFragmentPlan(sql);
        assertContains(plan, "  3:ANALYTIC\n" +
                "  |  functions: [, avg(3: k3), ]\n" +
                "  |  partition by: 3: k3\n" +
                "  |  order by: 3: k3 ASC");
        assertContains(plan, "  2:SORT\n" +
                "  |  order by: <slot 3> 3: k3 ASC");
    }

    @Test
    public void testWindowDuplicatePartition() throws Exception {
        String sql = "select max(v3) over (partition by v2,v2,v2 order by v2,v2) from t0;";
        String plan = getFragmentPlan(sql);
        assertContains(plan, "  2:SORT\n"
                + "  |  order by: <slot 2> 2: v2 ASC\n"
                + "  |  offset: 0");

    }

    @Test
    public void testWindowDuplicatedColumnInPartitionExprAndOrderByExpr() throws Exception {
        String sql = "select v1, sum(v2) over (partition by v1, v2 order by v2 desc) as sum1 from t0";
        String plan = getFragmentPlan(sql);
        Assert.assertNotNull(plan);
    }

    @Test
    public void testSupersetEnforce() throws Exception {
        String sql = "select * from (select v3, rank() over (partition by v1 order by v2) as j1 from t0) as x0 "
                + "join t1 on x0.v3 = t1.v4 order by x0.v3, t1.v4 limit 100;";
        getFragmentPlan(sql);
    }

    @Test
    public void testNtileWindowFunction() throws Exception {
        // Must have exactly one positive bigint integer parameter.
        String sql = "select v1, v2, NTILE(v2) over (partition by v1 order by v2) as j1 from t0";
        starRocksAssert.query(sql)
                .analysisError("The num_buckets parameter of NTILE must be a constant positive integer");

        sql = "select v1, v2, NTILE(1.1) over (partition by v1 order by v2) as j1 from t0";
        starRocksAssert.query(sql)
                .analysisError("The num_buckets parameter of NTILE must be a constant positive integer");

        sql = "select v1, v2, NTILE(0) over (partition by v1 order by v2) as j1 from t0";
        starRocksAssert.query(sql)
                .analysisError("The num_buckets parameter of NTILE must be a constant positive integer");

        sql = "select v1, v2, NTILE(-1) over (partition by v1 order by v2) as j1 from t0";
        starRocksAssert.query(sql)
                .analysisError("The num_buckets parameter of NTILE must be a constant positive integer");

        sql = "select v1, v2, NTILE('abc') over (partition by v1 order by v2) as j1 from t0";
        starRocksAssert.query(sql)
                .analysisError("The num_buckets parameter of NTILE must be a constant positive integer");

        sql = "select v1, v2, NTILE(null) over (partition by v1 order by v2) as j1 from t0";
        starRocksAssert.query(sql)
                .analysisError("The num_buckets parameter of NTILE must be a constant positive integer");

        sql = "select v1, v2, NTILE(1 + 2) over (partition by v1 order by v2) as j1 from t0";
        starRocksAssert.query(sql)
                .analysisError("The num_buckets parameter of NTILE must be a constant positive integer");

        sql = "select v1, v2, NTILE(9223372036854775808) over (partition by v1 order by v2) as j1 from t0";
        starRocksAssert.query(sql).analysisError("Number out of range");

        sql = "select v1, v2, NTILE((select v1 from t0)) over (partition by v1 order by v2) as j1 from t0";
        starRocksAssert.query(sql)
                .analysisError("The num_buckets parameter of NTILE must be a constant positive integer");

        sql = "select v1, v2, NTILE() " +
                "over (partition by v1 order by v2 rows between 1 preceding and 1 following) as j1 from t0";
        starRocksAssert.query(sql).analysisError("No matching function with signature: ntile()");

        // Windowing clause not allowed with NTILE.
        sql = "select v1, v2, NTILE(2) " +
                "over (partition by v1 order by v2 rows between 1 preceding and 1 following) as j1 from t0";
        starRocksAssert.query(sql).analysisError("Windowing clause not allowed");

        // Normal case.
        sql = "select v1, v2, NTILE(2) over (partition by v1 order by v2) as j1 from t0";
        String plan = getFragmentPlan(sql);
        assertContains(plan,
                "  3:ANALYTIC\n" +
                        "  |  functions: [, ntile(2), ]\n" +
                        "  |  partition by: 1: v1\n" +
                        "  |  order by: 2: v2 ASC\n" +
                        "  |  window: ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW\n" +
                        "  |  \n" +
                        "  2:SORT\n" +
                        "  |  order by: <slot 1> 1: v1 ASC, <slot 2> 2: v2 ASC\n" +
                        "  |  offset: 0");
    }

    @Test
    public void testRankingWindowWithoutPartitionPredicatePushDown() throws Exception {
        FeConstants.runningUnitTest = true;
        {
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk <= 4;";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  1:TOP-N\n" +
                    "  |  order by: <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0\n" +
                    "  |  limit: 4");
            sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk < 4;";
            plan = getFragmentPlan(sql);
            assertContains(plan, "  1:TOP-N\n" +
                    "  |  order by: <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0\n" +
                    "  |  limit: 4");
            sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk = 4;";
            plan = getFragmentPlan(sql);
            assertContains(plan, "  1:TOP-N\n" +
                    "  |  order by: <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0\n" +
                    "  |  limit: 4");
        }
        {
            // Two window function share the same sort group cannot be optimized
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (order by v2) as rk, " +
                    "        sum(v1) over (order by v2) as sm " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk <= 4;";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  1:SORT\n" +
                    "  |  order by: <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");

            sql = "select * from (\n" +
                    "    select *, " +
                    "        sum(v1) over (order by v2) as sm, " +
                    "        row_number() over (order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk <= 4;";
            plan = getFragmentPlan(sql);
            assertContains(plan, "  1:SORT\n" +
                    "  |  order by: <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
        }
        {
            // Two window function do not share the same sort group
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        sum(v1) over (order by v3) as sm," +
                    "        row_number() over (order by v2) as rk" +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk <= 4;";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  4:TOP-N\n" +
                    "  |  order by: <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0\n" +
                    "  |  limit: 4");
        }
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testRankingWindowWithoutPartitionLimitPushDown() throws Exception {
        FeConstants.runningUnitTest = true;
        {
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk limit 5;";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  1:TOP-N\n" +
                    "  |  order by: <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0\n" +
                    "  |  limit: 5");
        }
        {
            // Two window function share the same sort group cannot be optimized
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (order by v2) as rk, " +
                    "        sum(v1) over (order by v2) as sm " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk limit 5";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  1:SORT\n" +
                    "  |  order by: <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");

            sql = "select * from (\n" +
                    "    select *, " +
                    "        sum(v1) over (order by v2) as sm, " +
                    "        row_number() over (order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk limit 5";
            plan = getFragmentPlan(sql);
            assertContains(plan, "  1:SORT\n" +
                    "  |  order by: <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
        }
        {
            // Two window function do not share the same sort group
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        sum(v1) over (order by v3) as sm, " +
                    "        row_number() over (order by v2) as rk" +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk,sm limit 100,1";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  7:TOP-N\n" +
                    "  |  order by: <slot 5> 5: row_number() ASC, <slot 4> 4: sum(1: v1) ASC\n" +
                    "  |  offset: 0\n" +
                    "  |  limit: 101");
        }
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testRankingWindowWithPartitionPredicatePushDown() throws Exception {
        FeConstants.runningUnitTest = true;
        {
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk <= 4;";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  1:PARTITION-TOP-N\n" +
                    "  |  partition by: 3: v3 \n" +
                    "  |  partition limit: 4\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
            sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk < 4;";
            plan = getFragmentPlan(sql);
            assertContains(plan, "  1:PARTITION-TOP-N\n" +
                    "  |  partition by: 3: v3 \n" +
                    "  |  partition limit: 4\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
        }
        {
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        rank() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk <= 4;";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  1:PARTITION-TOP-N\n" +
                    "  |  type: RANK\n" +
                    "  |  partition by: 3: v3 \n" +
                    "  |  partition limit: 4\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
            sql = "select * from (\n" +
                    "    select *, " +
                    "        rank() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk = 4;";
            plan = getFragmentPlan(sql);
            assertContains(plan, "  1:PARTITION-TOP-N\n" +
                    "  |  type: RANK\n" +
                    "  |  partition by: 3: v3 \n" +
                    "  |  partition limit: 4\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
        }
        {
            // Do not support dense_rank by now
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        dense_rank() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk <= 4;";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  2:SORT\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0\n" +
                    "  |  \n" +
                    "  1:EXCHANGE");
        }
        {
            // Do not support multi partition by right now, this test need to be updated when supported
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (partition by v2, v3 order by v1) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "where rk <= 4;";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  2:SORT\n" +
                    "  |  order by: <slot 2> 2: v2 ASC, <slot 3> 3: v3 ASC, <slot 1> 1: v1 ASC\n" +
                    "  |  offset: 0\n" +
                    "  |  \n" +
                    "  1:EXCHANGE");
        }
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testRankingWindowWithPartitionLimitPushDown() throws Exception {
        FeConstants.runningUnitTest = true;
        {
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk limit 5";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  1:PARTITION-TOP-N\n" +
                    "  |  partition by: 3: v3 \n" +
                    "  |  partition limit: 5\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
        }
        {
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        rank() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk limit 10, 5";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  1:PARTITION-TOP-N\n" +
                    "  |  type: RANK\n" +
                    "  |  partition by: 3: v3 \n" +
                    "  |  partition limit: 15\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");

            sql = "select * from (\n" +
                    "    select *, " +
                    "        rank() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk,v2 limit 10, 5";
            plan = getFragmentPlan(sql);
            assertContains(plan, "  1:PARTITION-TOP-N\n" +
                    "  |  type: RANK\n" +
                    "  |  partition by: 3: v3 \n" +
                    "  |  partition limit: 15\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
        }
        {
            // order by direction mismatch
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        rank() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk desc limit 10, 5";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  2:SORT\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");

            sql = "select * from (\n" +
                    "    select *, " +
                    "        rank() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk desc,v2 limit 10, 5";
            plan = getFragmentPlan(sql);
            assertContains(plan, "  2:SORT\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
        }
        {
            // Order by column mismatch
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by v2 limit 5";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  2:SORT\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");

            sql = "select * from (\n" +
                    "    select *, " +
                    "        row_number() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by v2,rk limit 5";
            plan = getFragmentPlan(sql);
            assertContains(plan, "  2:SORT\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0");
        }
        {
            // Do not support dense_rank by now
            String sql = "select * from (\n" +
                    "    select *, " +
                    "        dense_rank() over (partition by v3 order by v2) as rk " +
                    "    from t0\n" +
                    ") sub_t0\n" +
                    "order by rk limit 5";
            String plan = getFragmentPlan(sql);
            assertContains(plan, "  2:SORT\n" +
                    "  |  order by: <slot 3> 3: v3 ASC, <slot 2> 2: v2 ASC\n" +
                    "  |  offset: 0\n" +
                    "  |  \n" +
                    "  1:EXCHANGE");
        }
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testRuntimeFilterPushWithoutPartition() throws Exception {
        String sql = "select * from " +
                "(select v1, sum(v2) over (order by v2 desc) as sum1 from t0) a," +
                "(select v1 from t0 where v1 = 1) b " +
                "where a.v1 = b.v1";

        String plan = getVerboseExplain(sql);
        assertContains(plan, "3:ANALYTIC\n" +
                "  |  functions: [, sum[([2: v2, BIGINT, true]); args: BIGINT; result: BIGINT; " +
                "args nullable: true; result nullable: true], ]\n" +
                "  |  order by: [2: v2, BIGINT, true] DESC\n" +
                "  |  window: RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW\n" +
                "  |  cardinality: 1\n" +
                "  |  probe runtime filters:\n" +
                "  |  - filter_id = 0, probe_expr = (1: v1)");
    }

    @Test
    public void testRuntimeFilterPushWithRightPartition() throws Exception {
        String sql = "select * from " +
                "(select v1, sum(v2) over (partition by v1 order by v2 desc) as sum1 from t0) a," +
                "(select v1 from t0 where v1 = 1) b " +
                "where a.v1 = b.v1";
        String plan = getVerboseExplain(sql);
        assertContains(plan, "  2:SORT\n" +
                "  |  order by: [1, BIGINT, true] ASC, [2, BIGINT, true] DESC\n" +
                "  |  offset: 0\n" +
                "  |  cardinality: 1\n" +
                "  |  probe runtime filters:\n" +
                "  |  - filter_id = 0, probe_expr = (1: v1)");
    }

    @Test
    public void testRuntimeFilterPushWithOtherPartition() throws Exception {
        String sql = "select * from " +
                "(select v1, v3, sum(v2) over (partition by v3 order by v2 desc) as sum1 from t0) a," +
                "(select v1 from t0 where v1 = 1) b " +
                "where a.v1 = b.v1";

        String plan = getVerboseExplain(sql);
        assertContains(plan, "3:ANALYTIC\n" +
                "  |  functions: [, sum[([2: v2, BIGINT, true]); args: BIGINT; " +
                "result: BIGINT; args nullable: true; result nullable: true], ]\n" +
                "  |  partition by: [3: v3, BIGINT, true]\n" +
                "  |  order by: [2: v2, BIGINT, true] DESC\n" +
                "  |  window: RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW\n" +
                "  |  cardinality: 1\n" +
                "  |  probe runtime filters:\n" +
                "  |  - filter_id = 0, probe_expr = (1: v1)");
    }
}
