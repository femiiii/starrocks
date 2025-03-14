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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/catalog/FunctionSet.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.catalog;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.MapEntry;
import com.starrocks.analysis.ArithmeticExpr;
import com.starrocks.analysis.FunctionName;
import com.starrocks.builtins.VectorizedBuiltinFunctions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FunctionSet {
    // For most build-in functions, it will return NullLiteral when params contain NullLiteral.
    // But a few functions need to handle NullLiteral differently, such as "if". It need to add
    // an attribute to LiteralExpr to mark null and check the attribute to decide whether to
    // replace the result with NullLiteral when function finished. It leaves to be realized.
    // Functions in this set is defined in `gensrc/script/starrocks_builtins_functions.py`,
    // and will be built automatically.

    // Date functions:
    public static final String CONVERT_TZ = "convert_tz";
    public static final String CURDATE = "curdate";
    public static final String CURRENT_TIMESTAMP = "current_timestamp";
    public static final String CURTIME = "curtime";
    public static final String CURRENT_TIME = "current_time";
    public static final String DATEDIFF = "datediff";
    public static final String DATE_ADD = "date_add";
    public static final String DATE_FORMAT = "date_format";
    public static final String DATE_SUB = "date_sub";
    public static final String DATE_TRUNC = "date_trunc";
    public static final String DAY = "day";
    public static final String DAYNAME = "dayname";
    public static final String DAYOFMONTH = "dayofmonth";
    public static final String DAYOFWEEK = "dayofweek";
    public static final String DAYOFYEAR = "dayofyear";
    public static final String FROM_DAYS = "from_days";
    public static final String FROM_UNIXTIME = "from_unixtime";
    public static final String HOUR = "hour";
    public static final String MINUTE = "minute";
    public static final String MONTH = "month";
    public static final String MONTHNAME = "monthname";
    public static final String NOW = "now";
    public static final String SECOND = "second";
    public static final String STR_TO_DATE = "str_to_date";
    public static final String TIMEDIFF = "timediff";
    public static final String TIMESTAMPADD = "timestampadd";
    public static final String TIMESTAMPDIFF = "timestampdiff";
    public static final String TO_DATE = "to_date";
    public static final String TO_DAYS = "to_days";
    public static final String UNIX_TIMESTAMP = "unix_timestamp";
    public static final String UTC_TIMESTAMP = "utc_timestamp";
    public static final String WEEKOFYEAR = "weekofyear";
    public static final String YEAR = "year";
    public static final String MINUTES_DIFF = "minutes_diff";
    public static final String HOURS_DIFF = "hours_diff";
    public static final String DAYS_DIFF = "days_diff";
    public static final String MONTHS_DIFF = "months_diff";
    public static final String SECONDS_DIFF = "seconds_diff";
    public static final String WEEKS_DIFF = "weeks_diff";
    public static final String YEARS_DIFF = "years_diff";
    public static final String QUARTER = "quarter";
    public static final String TIMESTAMP = "timestamp";
    public static final String TIME_TO_SEC = "time_to_sec";
    public static final String STR2DATE = "str2date";
    public static final String MICROSECONDS_ADD = "microseconds_add";
    public static final String MICROSECONDS_SUB = "microseconds_sub";
    public static final String YEARS_ADD = "years_add";
    public static final String YEARS_SUB = "years_sub";
    public static final String MONTHS_ADD = "months_add";
    public static final String MONTHS_SUB = "months_sub";
    public static final String DAYS_ADD = "days_add";
    public static final String DAYS_SUB = "days_sub";
    public static final String ADDDATE = "adddate";
    public static final String SUBDATE = "subdate";
    public static final String TIME_SLICE = "time_slice";
    public static final String DATE_SLICE = "date_slice";
    public static final String DATE_FLOOR = "date_floor";
    public static final String STRFTIME = "strftime";
    public static final String TIME_FORMAT = "time_format";
    public static final String ALIGNMENT_TIMESTAMP = "alignment_timestamp";
    public static final String SUBSTITUTE = "substitute";

    // Encryption functions:
    public static final String AES_DECRYPT = "aes_decrypt";
    public static final String AES_ENCRYPT = "aes_encrypt";
    public static final String FROM_BASE64 = "from_base64";
    public static final String TO_BASE64 = "to_base64";
    public static final String MD5 = "md5";
    public static final String MD5_SUM = "md5sum";
    public static final String MD5_SUM_NUMERIC = "md5sum_numeric";
    public static final String SHA2 = "sha2";
    public static final String SM3 = "sm3";

    // Geo functions:
    public static final String ST_ASTEXT = "st_astext";
    public static final String ST_ASWKT = "st_aswkt";
    public static final String ST_CIRCLE = "st_circle";
    public static final String ST_CONTAINS = "st_contains";
    public static final String ST_DISTANCE_SPHERE = "st_distance_sphere";
    public static final String ST_GEOMETRYFROMTEXT = "st_geometryfromtext";
    public static final String ST_GEOMFROMTEXT = "st_geomfromtext";
    public static final String ST_LINEFROMTEXT = "st_linefromtext";
    public static final String ST_LINESTRINGFROMTEXT = "st_linestringfromtext";
    public static final String ST_POINT = "st_point";
    public static final String ST_POLYGON = "st_polygon";
    public static final String ST_POLYFROMTEXT = "st_polyfromtext";
    public static final String ST_POLYGONFROMTEXT = "st_polygonfromtext";
    public static final String ST_X = "st_x";
    public static final String ST_Y = "st_y";

    // String functions
    public static final String APPEND_TRAILING_CHAR_IF_ABSENT = "append_trailing_char_if_absent";
    public static final String ASCII = "ascii";
    public static final String CHAR_LENGTH = "char_length";
    public static final String CONCAT = "concat";
    public static final String CONCAT_WS = "concat_ws";
    public static final String ENDS_WITH = "ends_with";
    public static final String FIND_IN_SET = "find_in_set";
    public static final String GROUP_CONCAT = "group_concat";
    public static final String INSTR = "instr";
    public static final String LCASE = "lcase";
    public static final String LEFT = "left";
    public static final String LENGTH = "length";
    public static final String LOCATE = "locate";
    public static final String LOWER = "lower";
    public static final String LPAD = "lpad";
    public static final String LTRIM = "ltrim";
    public static final String RTRIM = "rtrim";
    public static final String MONEY_FORMAT = "money_format";
    public static final String NULL_OR_EMPTY = "null_or_empty";
    public static final String REGEXP_EXTRACT = "regexp_extract";
    public static final String REGEXP_REPLACE = "regexp_replace";
    public static final String REPEAT = "repeat";
    public static final String REVERSE = "reverse";
    public static final String RIGHT = "right";
    public static final String RPAD = "rpad";
    public static final String SPLIT = "split";
    public static final String SPLIT_PART = "split_part";
    public static final String STARTS_WITH = "starts_with";
    public static final String STRLEFT = "strleft";
    public static final String STRRIGHT = "strright";
    public static final String HEX = "hex";
    public static final String UNHEX = "unhex";
    public static final String SUBSTR = "substr";
    public static final String SUBSTRING = "substring";
    public static final String SPACE = "space";
    public static final String PARSE_URL = "parse_url";
    public static final String TRIM = "trim";
    public static final String UPPER = "upper";

    // Json functions:
    public static final String JSON_ARRAY = "json_array";
    public static final String JSON_OBJECT = "json_object";
    public static final String PARSE_JSON = "parse_json";
    public static final String JSON_QUERY = "json_query";
    public static final String JSON_EXIST = "json_exists";
    public static final String JSON_EACH = "json_each";
    public static final String GET_JSON_DOUBLE = "get_json_double";
    public static final String GET_JSON_INT = "get_json_int";
    public static final String GET_JSON_STRING = "get_json_string";

    // Matching functions:
    public static final String LIKE = "like";
    public static final String REGEXP = "regexp";

    // Utility functions:
    public static final String CURRENT_VERSION = "current_version";
    public static final String LAST_QUERY_ID = "last_query_id";
    public static final String UUID = "uuid";
    public static final String UUID_NUMERIC = "uuid_numeric";
    public static final String SLEEP = "sleep";
    public static final String ISNULL = "isnull";
    public static final String ASSERT_TRUE = "assert_true";
    public static final String HOST_NAME = "host_name";
    // Aggregate functions:
    public static final String APPROX_COUNT_DISTINCT = "approx_count_distinct";
    public static final String AVG = "avg";
    public static final String COUNT = "count";
    public static final String HLL_UNION_AGG = "hll_union_agg";
    public static final String MAX = "max";
    public static final String MAX_BY = "max_by";
    public static final String MIN = "min";
    public static final String PERCENTILE_APPROX = "percentile_approx";
    public static final String PERCENTILE_CONT = "percentile_cont";
    public static final String RETENTION = "retention";
    public static final String STDDEV = "stddev";
    public static final String STDDEV_POP = "stddev_pop";
    public static final String STDDEV_SAMP = "stddev_samp";
    public static final String SUM = "sum";
    public static final String VARIANCE = "variance";
    public static final String VAR_POP = "var_pop";
    public static final String VARIANCE_POP = "variance_pop";
    public static final String VAR_SAMP = "var_samp";
    public static final String VARIANCE_SAMP = "variance_samp";
    public static final String ANY_VALUE = "any_value";
    public static final String SUM_DISTINCT = "sum_distinct";
    public static final String STD = "std";
    public static final String STDDEV_VAL = "stddev_val";
    public static final String HLL_UNION = "hll_union";
    public static final String HLL_RAW_AGG = "hll_raw_agg";
    public static final String HLL_RAW = "hll_raw";
    public static final String HLL_EMPTY = "hll_empty";
    public static final String NDV = "ndv";
    public static final String NDV_NO_FINALIZE = "ndv_no_finalize";
    public static final String MULTI_DISTINCT_COUNT = "multi_distinct_count";
    public static final String MULTI_DISTINCT_SUM = "multi_distinct_sum";
    public static final String DICT_MERGE = "dict_merge";
    public static final String WINDOW_FUNNEL = "window_funnel";
    public static final String DISTINCT_PC = "distinct_pc";
    public static final String DISTINCT_PCSA = "distinct_pcsa";
    public static final String HISTOGRAM = "histogram";

    // Bitmap functions:
    public static final String BITMAP_AND = "bitmap_and";
    public static final String BITMAP_ANDNOT = "bitmap_andnot";
    public static final String BITMAP_CONTAINS = "bitmap_contains";
    public static final String BITMAP_EMPTY = "bitmap_empty";
    public static final String BITMAP_FROM_STRING = "bitmap_from_string";
    public static final String BITMAP_HASH = "bitmap_hash";
    public static final String BITMAP_HAS_ANY = "bitmap_has_any";
    public static final String BITMAP_INTERSECT = "bitmap_intersect";
    public static final String BITMAP_MAX = "bitmap_max";
    public static final String BITMAP_MIN = "bitmap_min";
    public static final String BITMAP_OR = "bitmap_or";
    public static final String BITMAP_REMOVE = "bitmap_remove";
    public static final String BITMAP_TO_STRING = "bitmap_to_string";
    public static final String BITMAP_TO_ARRAY = "bitmap_to_array";
    public static final String BITMAP_UNION = "bitmap_union";
    public static final String BITMAP_XOR = "bitmap_xor";
    public static final String TO_BITMAP = "to_bitmap";
    public static final String BITMAP_COUNT = "bitmap_count";
    public static final String BITMAP_UNION_COUNT = "bitmap_union_count";
    public static final String BITMAP_UNION_INT = "bitmap_union_int";
    public static final String INTERSECT_COUNT = "intersect_count";
    public static final String BITMAP_DICT = "bitmap_dict";
    public static final String EXCHANGE_BYTES = "exchange_bytes";
    public static final String EXCHANGE_SPEED = "exchange_speed";
    // Array functions:
    public static final String ARRAY_AGG = "array_agg";
    public static final String ARRAY_CONCAT = "array_concat";
    public static final String ARRAY_DIFFERENCE = "array_difference";
    public static final String ARRAY_INTERSECT = "array_intersect";
    public static final String ARRAY_SLICE = "array_slice";
    public static final String ARRAYS_OVERLAP = "arrays_overlap";
    public static final String ARRAY_APPEND = "array_append";
    public static final String ARRAY_AVG = "array_avg";
    public static final String ARRAY_CONTAINS = "array_contains";
    public static final String ARRAY_JOIN = "array_join";
    public static final String ARRAY_DISTINCT = "array_distinct";
    public static final String ARRAY_LENGTH = "array_length";
    public static final String ARRAY_MAX = "array_max";
    public static final String ARRAY_MIN = "array_min";
    public static final String ARRAY_POSITION = "array_position";
    public static final String ARRAY_SORT = "array_sort";
    public static final String ARRAY_SUM = "array_sum";
    public static final String ARRAY_REMOVE = "array_remove";
    public static final String ARRAY_FILTER = "array_filter";
    public static final String ARRAY_SORTBY = "array_sortby";

    // Bit functions:
    public static final String BITAND = "bitand";
    public static final String BITNOT = "bitnot";
    public static final String BITOR = "bitor";
    public static final String BITXOR = "bitxor";
    public static final String BIT_SHIFT_LEFT = "bit_shift_left";
    public static final String BIT_SHIFT_RIGHT = "bit_shift_right";
    public static final String BIT_SHIFT_RIGHT_LOGICAL = "bit_shift_right_logical";

    // Hash functions:
    public static final String MURMUR_HASH3_32 = "murmur_hash3_32";

    // Percentile functions:
    public static final String PERCENTILE_APPROX_RAW = "percentile_approx_raw";
    public static final String PERCENTILE_EMPTY = "percentile_empty";
    public static final String PERCENTILE_HASH = "percentile_hash";
    public static final String PERCENTILE_UNION = "percentile_union";

    // Condition functions:
    public static final String COALESCE = "coalesce";
    public static final String IF = "if";
    public static final String IFNULL = "ifnull";
    public static final String NULLIF = "nullif";

    // Math functions:
    public static final String ABS = "abs";
    public static final String ACOS = "acos";
    public static final String ADD = "add";
    public static final String ASIN = "asin";
    public static final String ATAN = "atan";
    public static final String ATAN2 = "atan2";
    public static final String BIN = "bin";
    public static final String CEIL = "ceil";
    public static final String DCEIL = "dceil";
    public static final String CEILING = "ceiling";
    public static final String CONV = "conv";
    public static final String COS = "cos";
    public static final String COT = "cot";
    public static final String DEGRESS = "degress";
    public static final String DIVIDE = "divide";
    public static final String E = "e";
    public static final String EXP = "exp";
    public static final String DEXP = "dexp";
    public static final String FLOOR = "floor";
    public static final String DFLOOR = "dfloor";
    public static final String FMOD = "fmod";
    public static final String GREATEST = "greatest";
    public static final String LEAST = "least";
    public static final String LN = "ln";
    public static final String DLOG1 = "dlog1";
    public static final String LOG = "log";
    public static final String LOG2 = "log2";
    public static final String LOG10 = "log10";
    public static final String DLOG10 = "dlog10";
    public static final String MOD = "mod";
    public static final String MULTIPLY = "multiply";
    public static final String NEGATIVE = "negative";
    public static final String PI = "pi";
    public static final String PMOD = "pmod";
    public static final String POSITIVE = "positive";
    public static final String POW = "pow";
    public static final String POWER = "power";
    public static final String DPOW = "dpow";
    public static final String FPOW = "fpow";
    public static final String RADIANS = "radians";
    public static final String RAND = "rand";
    public static final String RANDOM = "random";
    public static final String ROUND = "round";
    public static final String DROUND = "dround";
    public static final String SIGN = "sign";
    public static final String SIN = "sin";
    public static final String SQRT = "sqrt";
    public static final String SUBTRACT = "subtract";
    public static final String DSQRT = "dsqrt";
    public static final String SQUARE = "square";
    public static final String TAN = "tan";
    public static final String TRUNCATE = "truncate";

    // Window functions:
    public static final String LEAD = "lead";
    public static final String LAG = "lag";
    public static final String FIRST_VALUE = "first_value";
    public static final String FIRST_VALUE_REWRITE = "first_value_rewrite";
    public static final String LAST_VALUE = "last_value";
    public static final String DENSE_RANK = "dense_rank";
    public static final String RANK = "rank";
    public static final String NTILE = "ntile";
    public static final String ROW_NUMBER = "row_number";

    // Other functions:
    public static final String HLL_HASH = "hll_hash";
    public static final String HLL_CARDINALITY = "hll_cardinality";
    public static final String DEFAULT_VALUE = "default_value";
    public static final String REPLACE_VALUE = "replace_value";

    // high-order functions related lambda functions
    public static final String ARRAY_MAP = "array_map";
    public static final String TRANSFORM = "transform";

    // JSON functions
    public static final Function JSON_QUERY_FUNC = new Function(
            new FunctionName(JSON_QUERY), new Type[] {Type.JSON, Type.VARCHAR}, Type.JSON, false);

    private static final Logger LOGGER = LogManager.getLogger(FunctionSet.class);

    private static final Set<Type> STDDEV_ARG_TYPE =
            ImmutableSet.<Type>builder()
                    .addAll(Type.FLOAT_TYPES)
                    .addAll(Type.INTEGER_TYPES)
                    .addAll(Type.DECIMAL_TYPES)
                    .build();

    private static final Set<Type> HISTOGRAM_TYPE =
            ImmutableSet.<Type>builder()
                    .addAll(Type.FLOAT_TYPES)
                    .addAll(Type.INTEGER_TYPES)
                    .addAll(Type.DECIMAL_TYPES)
                    .addAll(Type.STRING_TYPES)
                    .addAll(Type.DATE_TYPES)
                    .build();

    private static final Set<Type> MULTI_DISTINCT_COUNT_TYPES =
            ImmutableSet.<Type>builder()
                    .addAll(Type.INTEGER_TYPES)
                    .addAll(Type.FLOAT_TYPES)
                    .addAll(Type.DECIMAL_TYPES)
                    .addAll(Type.STRING_TYPES)
                    .add(Type.DATE)
                    .add(Type.DATETIME)
                    .add(Type.DECIMALV2)
                    .build();

    private static final Map<Type, Type> ARRAY_AGG_TYPES = ImmutableMap.<Type, Type>builder()
            .put(Type.BOOLEAN, Type.ARRAY_BOOLEAN)
            .put(Type.TINYINT, Type.ARRAY_TINYINT)
            .put(Type.SMALLINT, Type.ARRAY_SMALLINT)
            .put(Type.INT, Type.ARRAY_INT)
            .put(Type.BIGINT, Type.ARRAY_BIGINT)
            .put(Type.LARGEINT, Type.ARRAY_LARGEINT)
            .put(Type.FLOAT, Type.ARRAY_FLOAT)
            .put(Type.DOUBLE, Type.ARRAY_DOUBLE)
            .put(Type.VARCHAR, Type.ARRAY_VARCHAR)
            .put(Type.CHAR, Type.ARRAY_VARCHAR)
            .put(Type.DATE, Type.ARRAY_DATE)
            .put(Type.DATETIME, Type.ARRAY_DATETIME)
            .put(Type.DECIMAL32, Type.ARRAY_DECIMALV2)
            .put(Type.TIME, Type.ARRAY_DATETIME) // ??
            .put(Type.JSON, Type.ARRAY_JSON)
            .build();
    /**
     * Use for vectorized engine, but we can't use vectorized function directly, because we
     * need to check whether the expression tree can use vectorized function from bottom to
     * top, we must to re-analyze function_call_expr when vectorized function is can't used
     * if we choose to use the vectorized function here. So... we need bind vectorized function
     * to row function when init.
     */
    private final Map<String, List<Function>> vectorizedFunctions;

    // This contains the nullable functions, which cannot return NULL result directly for the NULL parameter.
    // This does not contain any user defined functions. All UDFs handle null values by themselves.
    private final ImmutableSet<String> notAlwaysNullResultWithNullParamFunctions =
            ImmutableSet.of(IF, CONCAT_WS, IFNULL, NULLIF, NULL_OR_EMPTY, COALESCE, BITMAP_HASH, PERCENTILE_HASH,
                    HLL_HASH, JSON_ARRAY, JSON_OBJECT);

    // If low cardinality string column with global dict, for some string functions,
    // we could evaluate the function only with the dict content, not all string column data.
    public final ImmutableSet<String> couldApplyDictOptimizationFunctions =
            ImmutableSet.of(APPEND_TRAILING_CHAR_IF_ABSENT, CONCAT, CONCAT_WS, HEX, LEFT, LIKE, LOWER, LPAD, LTRIM,
                    REGEXP_EXTRACT, REGEXP_REPLACE, REPEAT, REVERSE, RIGHT, RPAD, RTRIM, SPLIT_PART, SUBSTR, SUBSTRING,
                    TRIM, UPPER, IF);

    public static final Set<String> alwaysReturnNonNullableFunctions =
            ImmutableSet.<String>builder()
                    .add(FunctionSet.COUNT)
                    .add(FunctionSet.MULTI_DISTINCT_COUNT)
                    .add(FunctionSet.NULL_OR_EMPTY)
                    .add(FunctionSet.HLL_HASH)
                    .add(FunctionSet.HLL_UNION_AGG)
                    .add(FunctionSet.NDV)
                    .add(FunctionSet.APPROX_COUNT_DISTINCT)
                    .add(FunctionSet.BITMAP_UNION_INT)
                    .add(FunctionSet.BITMAP_UNION_COUNT)
                    .add(FunctionSet.BITMAP_COUNT)
                    .add(FunctionSet.CURDATE)
                    .add(FunctionSet.CURRENT_TIMESTAMP)
                    .add(FunctionSet.CURRENT_TIME)
                    .add(FunctionSet.NOW)
                    .add(FunctionSet.UTC_TIMESTAMP)
                    .add(FunctionSet.MD5_SUM)
                    .add(FunctionSet.MD5_SUM_NUMERIC)
                    .add(FunctionSet.BITMAP_EMPTY)
                    .add(FunctionSet.HLL_EMPTY)
                    .add(FunctionSet.EXCHANGE_BYTES)
                    .add(FunctionSet.EXCHANGE_SPEED)
                    .build();

    public static final Set<String> decimalRoundFunctions =
            ImmutableSet.<String>builder()
                    .add(TRUNCATE)
                    .add(ROUND)
                    .add(DROUND)
                    .build();

    public static final Set<String> nonDeterministicFunctions =
            ImmutableSet.<String>builder()
                    .add(RAND)
                    .add(RANDOM)
                    .add(UUID)
                    .add(SLEEP)
                    .build();

    public static final Set<String> onlyAnalyticUsedFunctions = ImmutableSet.<String>builder()
            .add(FunctionSet.DENSE_RANK)
            .add(FunctionSet.RANK)
            .add(FunctionSet.NTILE)
            .add(FunctionSet.ROW_NUMBER)
            .add(FunctionSet.FIRST_VALUE)
            .add(FunctionSet.LAST_VALUE)
            .add(FunctionSet.FIRST_VALUE_REWRITE)
            .build();

    public static final Set<String> varianceFunctions = ImmutableSet.<String>builder()
            .add(FunctionSet.VAR_POP)
            .add(FunctionSet.VAR_SAMP)
            .add(FunctionSet.VARIANCE)
            .add(FunctionSet.VARIANCE_POP)
            .add(FunctionSet.VARIANCE_SAMP)
            .add(FunctionSet.STD)
            .add(FunctionSet.STDDEV)
            .add(FunctionSet.STDDEV_POP)
            .add(FunctionSet.STDDEV_SAMP)
            .add(FunctionSet.STDDEV_VAL).build();

    public FunctionSet() {
        vectorizedFunctions = Maps.newHashMap();
    }

    /**
     * There are essential differences in the implementation of some functions for different
     * types params, which should be prohibited.
     *
     * @param desc
     * @param candicate
     * @return
     */
    public static boolean isCastMatchAllowed(Function desc, Function candicate) {
        final String functionName = desc.getFunctionName().getFunction();
        final Type[] descArgTypes = desc.getArgs();
        final Type[] candicateArgTypes = candicate.getArgs();
        if (functionName.equalsIgnoreCase(HEX)
                || functionName.equalsIgnoreCase(LEAD)
                || functionName.equalsIgnoreCase(LAG)) {
            final ScalarType descArgType = (ScalarType) descArgTypes[0];
            final ScalarType candicateArgType = (ScalarType) candicateArgTypes[0];
            if (functionName.equalsIgnoreCase(LEAD) ||
                    functionName.equalsIgnoreCase(LAG)) {
                // lead and lag function respect first arg type
                return descArgType.isNull() || descArgType.matchesType(candicateArgType);
            } else if (descArgType.isOnlyMetricType()) {
                // Bitmap, HLL, PERCENTILE type don't allow cast
                return false;
            } else {
                // The implementations of hex for string and int are different.
                return descArgType.isStringType() || !candicateArgType.isStringType();
            }
        }

        // ifnull, nullif(DATE, DATETIME) should return datetime, not bigint
        // if(boolean, DATE, DATETIME) should return datetime
        int arg_index = 0;
        if (functionName.equalsIgnoreCase(IFNULL) ||
                functionName.equalsIgnoreCase(NULLIF) ||
                functionName.equalsIgnoreCase(IF)) {
            if (functionName.equalsIgnoreCase(IF)) {
                arg_index = 1;
            }
            boolean descIsAllDateType = true;
            for (int i = arg_index; i < descArgTypes.length; ++i) {
                if (!descArgTypes[i].isDateType()) {
                    descIsAllDateType = false;
                    break;
                }
            }
            Type candicateArgType = candicateArgTypes[arg_index];
            if (descIsAllDateType && !candicateArgType.isDateType()) {
                return false;
            }
        }
        return true;
    }

    public void init() {
        ArithmeticExpr.initBuiltins(this);
        TableFunction.initBuiltins(this);
        VectorizedBuiltinFunctions.initBuiltins(this);
        initAggregateBuiltins();
    }

    public boolean isNotAlwaysNullResultWithNullParamFunctions(String funcName) {
        return notAlwaysNullResultWithNullParamFunctions.contains(funcName)
                || alwaysReturnNonNullableFunctions.contains(funcName);
    }

    public Function getFunction(Function desc, Function.CompareMode mode) {
        List<Function> fns = vectorizedFunctions.get(desc.functionName());
        if (fns == null) {
            return null;
        }
        // First check for identical
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_IDENTICAL)) {
                return checkPolymorphicFunction(f, desc.getArgs());
            }
        }
        if (mode == Function.CompareMode.IS_IDENTICAL) {
            return null;
        }

        // Next check for indistinguishable
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_INDISTINGUISHABLE)) {
                return checkPolymorphicFunction(f, desc.getArgs());
            }
        }
        if (mode == Function.CompareMode.IS_INDISTINGUISHABLE) {
            return null;
        }

        // Next check for strict supertypes
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_SUPERTYPE_OF) && isCastMatchAllowed(desc, f)) {
                return checkPolymorphicFunction(f, desc.getArgs());
            }
        }
        if (mode == Function.CompareMode.IS_SUPERTYPE_OF) {
            return null;
        }

        // Finally, check for non-strict supertypes
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF) && isCastMatchAllowed(desc, f)) {
                return checkPolymorphicFunction(f, desc.getArgs());
            }
        }
        return null;
    }

    private void addBuiltInFunction(Function fn) {
        Preconditions.checkArgument(!fn.getReturnType().isPseudoType() || fn.isPolymorphic(), fn.toString());
        if (getFunction(fn, Function.CompareMode.IS_INDISTINGUISHABLE) != null) {
            return;
        }
        fn.setIsNullable(!alwaysReturnNonNullableFunctions.contains(fn.functionName()));
        List<Function> fns = vectorizedFunctions.computeIfAbsent(fn.functionName(), k -> Lists.newArrayList());
        fns.add(fn);
    }

    // for vectorized engine
    public void addVectorizedScalarBuiltin(long fid, String fnName, boolean varArgs,
                                           Type retType, Type... args) {

        List<Type> argsType = Arrays.stream(args).collect(Collectors.toList());
        addVectorizedBuiltin(ScalarFunction.createVectorizedBuiltin(fid, fnName, argsType, varArgs, retType));
    }

    private void addVectorizedBuiltin(Function fn) {
        if (findVectorizedFunction(fn) != null) {
            return;
        }
        fn.setCouldApplyDictOptimize(couldApplyDictOptimizationFunctions.contains(fn.functionName()));
        fn.setIsNullable(!alwaysReturnNonNullableFunctions.contains(fn.functionName()));
        List<Function> fns = vectorizedFunctions.computeIfAbsent(fn.functionName(), k -> Lists.newArrayList());
        fns.add(fn);
    }

    private Function findVectorizedFunction(Function desc) {
        List<Function> fns = vectorizedFunctions.get(desc.functionName());

        if (fns == null) {
            return null;
        }

        // First check for identical
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_IDENTICAL)) {
                return f;
            }
        }

        // Next check for indistinguishable
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_INDISTINGUISHABLE)) {
                return f;
            }
        }
        return null;

    }

    /**
     * Adds a builtin to this database. The function must not already exist.
     */
    public void addBuiltin(Function fn) {
        addBuiltInFunction(fn);
    }

    // Populate all the aggregate builtins in the globalStateMgr.
    // null symbols indicate the function does not need that step of the evaluation.
    // An empty symbol indicates a TODO for the BE to implement the function.
    private void initAggregateBuiltins() {
        // count(*)
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.COUNT,
                new ArrayList<>(), Type.BIGINT, Type.BIGINT, false, true, true));

        // EXCHANGE_BYTES/_SPEED with various arguments
        addBuiltin(AggregateFunction.createBuiltin(EXCHANGE_BYTES,
                Lists.newArrayList(Type.ANY_ELEMENT), Type.BIGINT, Type.BIGINT, true,
                true, false, true));

        addBuiltin(AggregateFunction.createBuiltin(EXCHANGE_SPEED,
                Lists.newArrayList(Type.ANY_ELEMENT), Type.VARCHAR, Type.BIGINT, true,
                true, false, true));

        for (Type t : Type.getSupportedTypes()) {
            if (t.isFunctionType()) {
                continue;
            }
            if (t.isNull()) {
                continue; // NULL is handled through type promotion.
            }
            if (t.isChar()) {
                continue; // promoted to STRING
            }
            // Count
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.COUNT,
                    Lists.newArrayList(t), Type.BIGINT, Type.BIGINT, false, true, true));

            if (t.isPseudoType()) {
                continue; // Only function `Count` support pseudo types now.
            }

            // Min
            addBuiltin(AggregateFunction.createBuiltin(MIN,
                    Lists.newArrayList(t), t, t, true, true, false));

            // Max
            addBuiltin(AggregateFunction.createBuiltin(MAX,
                    Lists.newArrayList(t), t, t, true, true, false));

            // MAX_BY
            for (Type t1 : Type.getSupportedTypes()) {
                if (t1.isFunctionType() || t1.isNull() || t1.isChar() || t1.isPseudoType()) {
                    continue;
                }
                addBuiltin(AggregateFunction.createBuiltin(MAX_BY,
                        Lists.newArrayList(t1, t), t1, Type.VARBINARY, true, true, false));
            }

            // NDV
            addBuiltin(AggregateFunction.createBuiltin(NDV,
                    Lists.newArrayList(t), Type.BIGINT, Type.VARBINARY,
                    true, false, true));

            // ANY_VALUE
            addBuiltin(AggregateFunction.createBuiltin(ANY_VALUE,
                    Lists.newArrayList(t), t, t, true, false, false));

            //APPROX_COUNT_DISTINCT
            //alias of ndv, compute approx count distinct use HyperLogLog
            addBuiltin(AggregateFunction.createBuiltin(APPROX_COUNT_DISTINCT,
                    Lists.newArrayList(t), Type.BIGINT, Type.VARBINARY,
                    true, false, true));

            addBuiltin(AggregateFunction.createBuiltin(HLL_RAW,
                    Lists.newArrayList(t), Type.HLL, Type.VARBINARY,
                    true, false, true));

            // BITMAP_UNION_INT
            addBuiltin(AggregateFunction.createBuiltin(BITMAP_UNION_INT,
                    Lists.newArrayList(t), Type.BIGINT, Type.BITMAP,
                    true, false, true));

            // INTERSECT_COUNT
            addBuiltin(AggregateFunction.createBuiltin(INTERSECT_COUNT,
                    Lists.newArrayList(Type.BITMAP, t, t), Type.BIGINT, Type.VARCHAR, true,
                    true, false, true));
        }

        // MULTI_DISTINCT_COUNTM
        for (Type type: MULTI_DISTINCT_COUNT_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.MULTI_DISTINCT_COUNT, Lists.newArrayList(type),
                    Type.BIGINT,
                    Type.VARBINARY,
                    false, true, true));
        }

        // Sum
        registerBuiltinSumAggFunction(SUM);
        registerBuiltinSumAggFunction(SUM_DISTINCT);
        // MultiDistinctSum
        registerBuiltinMultiDistinctSumAggFunction();

        // Avg
        registerBuiltinAvgAggFunction();

        // Stddev
        registerBuiltinStddevAggFunction();

        // Percentile
        registerBuiltinPercentileAggFunction();

        // ArrayAgg
        registerBuiltinArrayAggFunction();

        // HLL_UNION_AGG
        addBuiltin(AggregateFunction.createBuiltin(HLL_UNION_AGG,
                Lists.newArrayList(Type.HLL), Type.BIGINT, Type.HLL,
                true, true, true));

        // HLL_UNION
        addBuiltin(AggregateFunction.createBuiltin(HLL_UNION,
                Lists.newArrayList(Type.HLL), Type.HLL, Type.HLL,
                true, false, true));

        // HLL_RAW_AGG is alias of HLL_UNION
        addBuiltin(AggregateFunction.createBuiltin(HLL_RAW_AGG,
                Lists.newArrayList(Type.HLL), Type.HLL, Type.HLL,
                true, false, true));

        // Bitmap
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_UNION, Lists.newArrayList(Type.BITMAP),
                Type.BITMAP,
                Type.BITMAP,
                true, false, true));

        addBuiltin(AggregateFunction.createBuiltin(BITMAP_UNION_COUNT, Lists.newArrayList(Type.BITMAP),
                Type.BIGINT,
                Type.BITMAP,
                true, true, true));
        // TODO(ml): supply function symbol
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_INTERSECT, Lists.newArrayList(Type.BITMAP),
                Type.BITMAP, Type.BITMAP,
                true, false, true));

        // Retention
        addBuiltin(AggregateFunction.createBuiltin(RETENTION, Lists.newArrayList(Type.ARRAY_BOOLEAN),
                Type.ARRAY_BOOLEAN, Type.BIGINT, false, false, false));

        // Group_concat(string)
        addBuiltin(AggregateFunction.createBuiltin(GROUP_CONCAT,
                Lists.newArrayList(Type.VARCHAR), Type.VARCHAR, Type.VARCHAR,
                false, false, false));
        // Group_concat(string, string)
        addBuiltin(AggregateFunction.createBuiltin(GROUP_CONCAT,
                Lists.newArrayList(Type.VARCHAR, Type.VARCHAR), Type.VARCHAR, Type.VARCHAR,
                false, false, false));

        // Type.DATE must before Type.DATATIME, because DATE could be considered as DATETIME.
        addBuiltin(AggregateFunction.createBuiltin(WINDOW_FUNNEL,
                Lists.newArrayList(Type.BIGINT, Type.DATE, Type.INT, Type.ARRAY_BOOLEAN),
                Type.INT, Type.ARRAY_BIGINT, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(WINDOW_FUNNEL,
                Lists.newArrayList(Type.BIGINT, Type.DATETIME, Type.INT, Type.ARRAY_BOOLEAN),
                Type.INT, Type.ARRAY_BIGINT, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(WINDOW_FUNNEL,
                Lists.newArrayList(Type.BIGINT, Type.INT, Type.INT, Type.ARRAY_BOOLEAN),
                Type.INT, Type.ARRAY_BIGINT, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(WINDOW_FUNNEL,
                Lists.newArrayList(Type.BIGINT, Type.BIGINT, Type.INT, Type.ARRAY_BOOLEAN),
                Type.INT, Type.ARRAY_BIGINT, false, false, false));

        // analytic functions
        // Rank
        addBuiltin(AggregateFunction.createAnalyticBuiltin(RANK,
                Collections.emptyList(), Type.BIGINT, Type.VARBINARY));
        // Dense rank
        addBuiltin(AggregateFunction.createAnalyticBuiltin(DENSE_RANK,
                Collections.emptyList(), Type.BIGINT, Type.VARBINARY));
        addBuiltin(AggregateFunction.createAnalyticBuiltin(ROW_NUMBER,
                Collections.emptyList(), Type.BIGINT, Type.BIGINT));
        addBuiltin(AggregateFunction.createAnalyticBuiltin(NTILE,
                Lists.newArrayList(Type.BIGINT), Type.BIGINT, Type.BIGINT));

        addBuiltin(AggregateFunction.createBuiltin(DICT_MERGE, Lists.newArrayList(Type.VARCHAR),
                Type.VARCHAR, Type.VARCHAR, true, false, false));

        addBuiltin(AggregateFunction.createBuiltin(DICT_MERGE, Lists.newArrayList(Type.ARRAY_VARCHAR),
                Type.VARCHAR, Type.VARCHAR, true, false, false));

        for (Type t : Type.getSupportedTypes()) {
            // null/char/time is handled through type promotion
            // TODO: array/json/pseudo is not supported yet
            if (!t.canBeWindowFunctionArgumentTypes()) {
                continue;
            }
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    FIRST_VALUE, Lists.newArrayList(t), t, t));
            // Implements FIRST_VALUE for some windows that require rewrites during planning.
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    FIRST_VALUE_REWRITE, Lists.newArrayList(t, Type.BIGINT), t, t));

            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LAST_VALUE, Lists.newArrayList(t), t, t));

            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LAG, Lists.newArrayList(t, Type.BIGINT, t), t, t));
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LEAD, Lists.newArrayList(t, Type.BIGINT, t), t, t));

            // lead() and lag() the default offset and the default value should be
            // rewritten to call the overrides that take all parameters.
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LAG, Lists.newArrayList(t), t, t));
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LAG, Lists.newArrayList(t, Type.BIGINT), t, t));
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LEAD, Lists.newArrayList(t), t, t));
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LEAD, Lists.newArrayList(t, Type.BIGINT), t, t));
        }

        for (Type t : HISTOGRAM_TYPE) {
            addBuiltin(AggregateFunction.createBuiltin(HISTOGRAM,
                    Lists.newArrayList(t, Type.INT, Type.DOUBLE), Type.VARCHAR, Type.VARCHAR,
                    false, false, false));
        }
    }

    private void registerBuiltinSumAggFunction(String name) {
        for (ScalarType type: Type.FLOAT_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(name,
                    Lists.newArrayList(type), Type.DOUBLE, Type.DOUBLE,
                    false, true, false));
        }
        for (ScalarType type: Type.INTEGER_TYPES) {
            if (type.isLargeint()) {
                addBuiltin(AggregateFunction.createBuiltin(name,
                        Lists.newArrayList(Type.LARGEINT), Type.LARGEINT, Type.LARGEINT, false, true, false));
            } else {
                addBuiltin(AggregateFunction.createBuiltin(name,
                        Lists.newArrayList(type), Type.BIGINT, Type.BIGINT,
                        false, true, false));
            }
        }
        for (ScalarType type: Type.DECIMAL_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(name,
                    Lists.newArrayList(type), Type.DECIMAL128, Type.DECIMAL128,
                    false, true, false));
        }
        addBuiltin(AggregateFunction.createBuiltin(name,
                Lists.newArrayList(Type.DECIMALV2), Type.DECIMALV2, Type.DECIMALV2, false, true, false));
    }

    private void registerBuiltinMultiDistinctSumAggFunction() {
        for (ScalarType type: Type.FLOAT_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                    Lists.newArrayList(type), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
        }
        for (ScalarType type: Type.INTEGER_TYPES) {
            if (type.isLargeint()) {
                addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                        Lists.newArrayList(type), Type.LARGEINT, Type.VARBINARY,
                        false, true, false));
            } else {
                addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                        Lists.newArrayList(type), Type.BIGINT, Type.VARBINARY,
                        false, true, false));
            }
        }
        for (ScalarType type: Type.DECIMAL_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                    Lists.newArrayList(type), Type.DECIMAL128, Type.VARBINARY,
                    false, true, false));
        }
        addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                Lists.newArrayList(Type.DECIMALV2), Type.DECIMALV2, Type.VARBINARY, false, true, false));
    }
    private void registerBuiltinAvgAggFunction() {
        // TODO: switch to CHAR(sizeof(AvgIntermediateType) when that becomes available
        for (ScalarType type: Type.FLOAT_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(AVG,
                    Lists.newArrayList(type), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
        }
        for (ScalarType type: Type.INTEGER_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(AVG,
                    Lists.newArrayList(type), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
        }
        for (ScalarType type: Type.DECIMAL_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(AVG,
                    Lists.newArrayList(type), Type.DECIMAL128, Type.VARBINARY,
                    false, true, false));
        }
        addBuiltin(AggregateFunction.createBuiltin(AVG,
                Lists.newArrayList(Type.DECIMALV2), Type.DECIMALV2, Type.VARCHAR,
                false, true, false));
        // Avg(Timestamp)
        addBuiltin(AggregateFunction.createBuiltin(AVG,
                Lists.newArrayList(Type.DATE), Type.DATE, Type.VARBINARY,
                false, true, false));
        addBuiltin(AggregateFunction.createBuiltin(AVG,
                Lists.newArrayList(Type.DATETIME), Type.DATETIME, Type.DATETIME,
                false, true, false));
    }
    private void registerBuiltinStddevAggFunction() {
        for (Type t: STDDEV_ARG_TYPE) {
            addBuiltin(AggregateFunction.createBuiltin(STDDEV,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(STDDEV_SAMP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(STDDEV_POP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(STD,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VARIANCE,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VARIANCE_SAMP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VAR_SAMP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VARIANCE_POP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VAR_POP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
        }
    }

    private void registerBuiltinPercentileAggFunction() {
        //PercentileApprox
        addBuiltin(AggregateFunction.createBuiltin(PERCENTILE_APPROX,
                Lists.newArrayList(Type.DOUBLE, Type.DOUBLE), Type.DOUBLE, Type.VARBINARY,
                false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(PERCENTILE_APPROX,
                Lists.newArrayList(Type.DOUBLE, Type.DOUBLE, Type.DOUBLE), Type.DOUBLE, Type.VARBINARY,
                false, false, false));

        addBuiltin(AggregateFunction.createBuiltin(PERCENTILE_UNION,
                Lists.newArrayList(Type.PERCENTILE), Type.PERCENTILE, Type.PERCENTILE,
                false, false, false));

        // PercentileCont
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.PERCENTILE_CONT,
                Lists.newArrayList(Type.DATE, Type.DOUBLE), Type.DATE, Type.VARBINARY,
                false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.PERCENTILE_CONT,
                Lists.newArrayList(Type.DATETIME, Type.DOUBLE), Type.DATETIME, Type.VARBINARY,
                false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.PERCENTILE_CONT,
                Lists.newArrayList(Type.DOUBLE, Type.DOUBLE), Type.DOUBLE, Type.VARBINARY,
                false, false, false));
    }

    private void registerBuiltinArrayAggFunction() {
        for (Map.Entry<Type, Type>  entry: ARRAY_AGG_TYPES.entrySet()) {
         addBuiltin(AggregateFunction.createBuiltin(FunctionSet.ARRAY_AGG,
                Lists.newArrayList(entry.getKey()), entry.getValue(), entry.getValue(),
                false, false, false));
        }
    }

    public List<Function> getBuiltinFunctions() {
        List<Function> builtinFunctions = Lists.newArrayList();
        for (Map.Entry<String, List<Function>> entry : vectorizedFunctions.entrySet()) {
            builtinFunctions.addAll(entry.getValue());
        }
        return builtinFunctions;
    }

    /**
     * Inspired by https://github.com/postgres/postgres/blob/master/src/backend/parser/parse_coerce.c#L1934
     * <p>
     * Make sure a polymorphic function is legally callable, and deduce actual argument and result types.
     * <p>
     * If any polymorphic pseudotype is used in a function's arguments or return type, we make sure the
     * actual data types are consistent with each other.
     * 1) If return type is ANYELEMENT, and any argument is ANYELEMENT, use the
     * argument's actual type as the function's return type.
     * 2) If return type is ANYARRAY, and any argument is ANYARRAY, use the
     * argument's actual type as the function's return type.
     * 3) Otherwise, if return type is ANYELEMENT or ANYARRAY, and there is
     * at least one ANYELEMENT, ANYARRAY input, deduce the return type from those inputs, or return null
     * if we can't.
     * </p>
     * <p>
     * Like PostgreSQL, two pseudo-types of special interest are ANY_ARRAY and ANY_ELEMENT, which are collectively
     * called polymorphic types. Any function declared using these types is said to be a polymorphic function.
     * A polymorphic function can operate on many different data types, with the specific data type(s) being
     * determined by the data types actually passed to it in a particular call.
     * <p>
     * Polymorphic arguments and results are tied to each other and are resolved to a specific data type when a
     * query calling a polymorphic function is parsed. Each position (either argument or return value) declared
     * as ANY_ELEMENT is allowed to have any specific actual data type, but in any given call they must all be
     * the same actual type. Each position declared as ANY_ARRAY can have any array data type, but similarly they
     * must all be the same type. Furthermore, if there are positions declared ANY_ARRAY and others declared
     * ANY_ELEMENT, the actual array type in the ANY_ARRAY positions must be an array whose elements are the same
     * type appearing in the ANY_ELEMENT positions.
     * <p>
     * Thus, when more than one argument position is declared with a polymorphic type, the net effect is that only
     * certain combinations of actual argument types are allowed. For example, a function declared as
     * equal(ANY_ELEMENT, ANY_ELEMENT) will take any two input values, so long as they are of the same data type.
     * <p>
     * When the return value of a function is declared as a polymorphic type, there must be at least one argument
     * position that is also polymorphic, and the actual data type supplied as the argument determines the actual
     * result type for that call. For example, if there were not already an array subscripting mechanism, one
     * could define a function that implements subscripting as subscript(ANY_ARRAY, INT) returns ANY_ELEMENT. This
     * declaration constrains the actual first argument to be an array type, and allows the parser to infer the
     * correct result type from the actual first argument's type.
     * </p>
     * TODO(zhuming): throws an exception on error, instead of return null.
     */
    private Function checkPolymorphicFunction(Function fn, Type[] paramTypes) {
        if (!fn.isPolymorphic()) {
            return fn;
        }
        Type[] declTypes = fn.getArgs();
        Type[] realTypes = Arrays.copyOf(declTypes, declTypes.length);
        ArrayType typeArray = null;
        Type typeElement = null;
        MapType typeMap = null;
        Type retType = fn.getReturnType();
        for (int i = 0; i < declTypes.length; i++) {
            Type declType = declTypes[i];
            Type realType = paramTypes[i];
            if (declType instanceof AnyArrayType) {
                if (realType.isNull()) {
                    continue;
                }
                if (typeArray == null) {
                    typeArray = (ArrayType) realType;
                } else if ((typeArray = (ArrayType) getSuperType(typeArray, realType)) == null) {
                    LOGGER.warn("could not determine polymorphic type because input has non-match types");
                    return null;
                }
            } else if (declType instanceof AnyMapType) {
                if (realType.isNull()) {
                    continue;
                }
                if (typeMap == null) {
                    typeMap = (MapType) realType;
                } else {
                    LOGGER.warn("could not determine polymorphic type because input has two map types");
                    return null;
                }
            } else if (declType instanceof AnyElementType) {
                if (realType.isNull()) {
                    continue;
                }
                if (typeElement == null) {
                    typeElement = realType;
                } else if ((typeElement = getSuperType(typeElement, realType)) == null) {
                    LOGGER.warn("could not determine polymorphic type because input has non-match types");
                    return null;
                }
            } else if (declType.matchesType(realType) || Type.canCastTo(realType, declType)) { // non-pseudo types
                continue;
            } else {
                LOGGER.warn("has unhandled pseudo type '{}'", declType);
                return null;
            }
        }

        if (typeArray != null && typeElement != null) {
            typeArray = (ArrayType) getSuperType(typeArray, new ArrayType(typeElement));
            if (typeArray == null) {
                LOGGER.warn("could not determine polymorphic type because has non-match types");
                return null;
            }
            typeElement = typeArray.getItemType();
        } else if (typeArray != null) {
            typeElement = typeArray.getItemType();
        } else if (typeElement != null) {
            typeArray = new ArrayType(typeElement);
        } else {
            typeElement = Type.NULL;
            typeArray = new ArrayType(Type.NULL);
        }

        if (!typeArray.getItemType().matchesType(typeElement)) {
            LOGGER.warn("could not determine polymorphic type because has non-match types");
            return null;
        }

        if (typeMap != null) {
            if (retType instanceof AnyArrayType) {
                if (fn.functionName().equals("map_keys")) {
                    retType = new ArrayType(typeMap.getKeyType());
                } else if (fn.functionName().equals("map_values")) {
                    retType = new ArrayType(typeMap.getValueType());
                } else {
                    LOGGER.warn("not supported map function");
                    return null;
                }
            }
        } else {
            if (retType instanceof AnyArrayType) {
                retType = typeArray;
            } else if (retType instanceof AnyElementType) {
                retType = typeElement;
            } else if (!(fn instanceof TableFunction)) { //TableFunction don't use retType
                assert !retType.isPseudoType();
            }
        }

        for (int i = 0; i < declTypes.length; i++) {
            if (declTypes[i] instanceof AnyArrayType) {
                realTypes[i] = typeArray;
            } else if(declTypes[i] instanceof AnyMapType) {
                realTypes[i] = typeMap;
            } else if (declTypes[i] instanceof AnyElementType) {
                realTypes[i] = typeElement;
            } else {
                realTypes[i] = declTypes[i];
            }
        }

        if (fn instanceof ScalarFunction) {
            ScalarFunction newFn = new ScalarFunction(fn.getFunctionName(), Arrays.asList(realTypes), retType,
                    fn.getLocation(), ((ScalarFunction) fn).getSymbolName(), ((ScalarFunction) fn).getPrepareFnSymbol(),
                    ((ScalarFunction) fn).getCloseFnSymbol());
            newFn.setFunctionId(fn.getFunctionId());
            newFn.setChecksum(fn.getChecksum());
            newFn.setBinaryType(fn.getBinaryType());
            newFn.setHasVarArgs(fn.hasVarArgs());
            newFn.setId(fn.getId());
            newFn.setUserVisible(fn.isUserVisible());
            return newFn;
        }
        if (fn instanceof AggregateFunction) {
            AggregateFunction newFn = new AggregateFunction(fn.getFunctionName(), Arrays.asList(realTypes), retType,
                    ((AggregateFunction) fn).getIntermediateType(), fn.hasVarArgs());
            newFn.setFunctionId(fn.getFunctionId());
            newFn.setChecksum(fn.getChecksum());
            newFn.setBinaryType(fn.getBinaryType());
            newFn.setHasVarArgs(fn.hasVarArgs());
            newFn.setId(fn.getId());
            newFn.setUserVisible(fn.isUserVisible());
            return newFn;
        }
        if (fn instanceof TableFunction) {
            // Because unnest is a variadic function, and the types of multiple parameters may be inconsistent,
            // the current SR variadic function parsing can only support variadic parameters of the same type.
            // The unnest is treated specially here, and the type of the child is directly used as the unnest function type.
            if (fn.functionName().equals("unnest")) {
                List<Type> realTableFnRetTypes = new ArrayList<>();
                for (Type paramType : paramTypes) {
                    if (!paramType.isArrayType()) {
                        return null;
                    }
                    Type t = ((ArrayType) paramType).getItemType();
                    realTableFnRetTypes.add(t);
                }
                return new TableFunction(fn.getFunctionName(), ((TableFunction) fn).getDefaultColumnNames(),
                        Arrays.asList(paramTypes), realTableFnRetTypes);
            }

            TableFunction tableFunction = (TableFunction) fn;
            List<Type> tableFnRetTypes = tableFunction.getTableFnReturnTypes();
            List<Type> realTableFnRetTypes = new ArrayList<>();
            for (Type t : tableFnRetTypes) {
                if (t instanceof AnyArrayType) {
                    realTableFnRetTypes.add(typeArray);
                } else if (t instanceof AnyElementType) {
                    realTableFnRetTypes.add(typeElement);
                } else {
                    assert !retType.isPseudoType();
                }
            }

            return new TableFunction(fn.getFunctionName(), ((TableFunction) fn).getDefaultColumnNames(),
                    Arrays.asList(realTypes), realTableFnRetTypes);
        }
        LOGGER.error("polymorphic function has unknown type: {}", fn);
        return null;
    }

    Type getSuperType(Type t1, Type t2) {
        if (t1.matchesType(t2)) {
            return t1;
        }
        if (t1.isNull()) {
            return t2;
        }
        if (t2.isNull()) {
            return t1;
        }
        if (t1.isFixedPointType() && t2.isFixedPointType()) {
            Type commonType = Type.getCommonType(t1, t2);
            return commonType.isValid() ? commonType : null;
        }
        if (t1.isArrayType() && t2.isArrayType()) {
            Type superElementType = getSuperType(((ArrayType) t1).getItemType(), ((ArrayType) t2).getItemType());
            return superElementType != null ? new ArrayType(superElementType) : null;
        }
        if (t1.isMapType() && t2.isMapType()) {
            Type superKeyType = getSuperType(((MapType) t1).getKeyType(), ((MapType) t2).getKeyType());
            Type superValueType = getSuperType(((MapType) t1).getValueType(), ((MapType) t2).getValueType());
            return superKeyType != null && superValueType != null ? new MapType(superKeyType, superValueType) : null;
        }
        return null;
    }
}
