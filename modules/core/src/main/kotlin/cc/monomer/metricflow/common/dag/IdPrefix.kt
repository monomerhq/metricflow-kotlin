package cc.monomer.metricflow.common.dag

/**
 * A prefix for generating IDs — e.g. prefix `dfp` → id `dfp_0`.
 *
 * Port of `metricflow_semantics.dag.id_prefix.IdPrefix`. The Python class is
 * abstract; concrete prefixes come from [StaticIdPrefix] (a large enum) and
 * [DynamicIdPrefix] (an ad-hoc string).
 */
sealed interface IdPrefix {
    val strValue: String
}

/** ID prefix backed by any string. Port of `DynamicIdPrefix`. */
@JvmInline
value class DynamicIdPrefix(val prefix: String) : IdPrefix {
    override val strValue: String get() = prefix
}

/**
 * Catalog of every prefix the engine uses for sequential ID generation.
 *
 * Port of `metricflow_semantics.dag.id_prefix.StaticIdPrefix`. The names map
 * 1:1 to the Python enum members; the [strValue] strings are preserved
 * exactly because they appear in generated SQL alias names and snapshot
 * fixtures (`dfp_0`, `cr_3`, etc.).
 */
enum class StaticIdPrefix(override val strValue: String) : IdPrefix {
    METRIC_EVALUATION_NODE__SIMPLE_METRICS_QUERY("smp"),
    METRIC_EVALUATION_NODE__CUMULATIVE_METRIC_QUERY("cml"),
    METRIC_EVALUATION_NODE__CONVERSION_METRIC_QUERY("cnv"),
    METRIC_EVALUATION_NODE__DERIVED_METRIC_QUERY("drv"),
    METRIC_EVALUATION_NODE__TOP_LEVEL_QUERY("tpl"),

    DATAFLOW_NODE_AGGREGATE_ID_PREFIX("am"),
    DATAFLOW_NODE_COMPUTE_METRICS_ID_PREFIX("cm"),
    DATAFLOW_NODE_JOIN_TO_STANDARD_OUTPUT_ID_PREFIX("jso"),
    DATAFLOW_NODE_JOIN_SELF_OVER_TIME_RANGE_ID_PREFIX("jotr"),
    DATAFLOW_NODE_ORDER_BY_LIMIT_ID_PREFIX("obl"),
    DATAFLOW_NODE_SELECTOR_ID_PREFIX("pfe"),
    DATAFLOW_NODE_READ_SQL_SOURCE_ID_PREFIX("rss"),
    DATAFLOW_NODE_WHERE_CONSTRAINT_ID_PREFIX("wcc"),
    DATAFLOW_NODE_WRITE_TO_RESULT_DATA_TABLE_ID_PREFIX("wrd"),
    DATAFLOW_NODE_WRITE_TO_RESULT_TABLE_ID_PREFIX("wrt"),
    DATAFLOW_NODE_COMBINE_AGGREGATED_OUTPUTS_ID_PREFIX("cao"),
    DATAFLOW_NODE_CONSTRAIN_TIME_RANGE_ID_PREFIX("ctr"),
    DATAFLOW_NODE_SET_METRIC_AGGREGATION_TIME("sma"),
    DATAFLOW_NODE_SEMI_ADDITIVE_JOIN_ID_PREFIX("saj"),
    DATAFLOW_NODE_JOIN_TO_TIME_SPINE_ID_PREFIX("jts"),
    DATAFLOW_NODE_JOIN_TO_CUSTOM_GRANULARITY_ID_PREFIX("jcg"),
    DATAFLOW_NODE_MIN_MAX_ID_PREFIX("mm"),
    DATAFLOW_NODE_ADD_UUID_COLUMN_PREFIX("auid"),
    DATAFLOW_NODE_JOIN_CONVERSION_EVENTS_PREFIX("jce"),
    DATAFLOW_NODE_WINDOW_REAGGREGATION_ID_PREFIX("wr"),
    DATAFLOW_NODE_ALIAS_SPECS_ID_PREFIX("as"),
    DATAFLOW_NODE_OFFSET_BY_CUSTOM_GRANULARITY_ID_PREFIX("obcg"),
    DATAFLOW_NODE_OFFSET_CUSTOM_GRANULARITY_ID_PREFIX("ocg"),

    SQL_EXPR_COLUMN_REFERENCE_ID_PREFIX("cr"),
    SQL_EXPR_COMPARISON_ID_PREFIX("cmp"),
    SQL_EXPR_FUNCTION_ID_PREFIX("fnc"),
    SQL_EXPR_PERCENTILE_ID_PREFIX("perc"),
    SQL_EXPR_STRING_ID_PREFIX("str"),
    SQL_EXPR_NULL_PREFIX("null"),
    SQL_EXPR_LOGICAL_OPERATOR_PREFIX("lo"),
    SQL_EXPR_STRING_LITERAL_PREFIX("sl"),
    SQL_EXPR_IS_NULL_PREFIX("isn"),
    SQL_EXPR_CAST_TO_TIMESTAMP_PREFIX("ctt"),
    SQL_EXPR_DATE_TRUNC("dt"),
    SQL_EXPR_SUBTRACT_TIME_INTERVAL_PREFIX("sti"),
    SQL_EXPR_ADD_TIME_PREFIX("ati"),
    SQL_EXPR_EXTRACT("ex"),
    SQL_EXPR_RATIO_COMPUTATION("rc"),
    SQL_EXPR_BETWEEN_PREFIX("betw"),
    SQL_EXPR_WINDOW_FUNCTION_ID_PREFIX("wfnc"),
    SQL_EXPR_GENERATE_UUID_PREFIX("uuid"),
    SQL_EXPR_CASE_PREFIX("case"),
    SQL_EXPR_ARITHMETIC_PREFIX("arit"),
    SQL_EXPR_INTEGER_PREFIX("int"),

    SQL_PLAN_SELECT_STATEMENT_ID_PREFIX("ss"),
    SQL_PLAN_TABLE_FROM_CLAUSE_ID_PREFIX("tfc"),
    SQL_PLAN_QUERY_FROM_CLAUSE_ID_PREFIX("qfc"),
    SQL_PLAN_CREATE_TABLE_AS_ID_PREFIX("cta"),
    SQL_PLAN_COMMON_TABLE_EXPRESSION_ID_PREFIX("cte"),

    EXEC_NODE_READ_SQL_QUERY("rsq"),
    EXEC_NODE_NOOP("noop"),
    EXEC_NODE_WRITE_TO_TABLE("wtt"),

    GROUP_BY_ITEM_RESOLUTION_DAG("gbir"),
    QUERY_GROUP_BY_ITEM_RESOLUTION_NODE("qr"),
    SIMPLE_METRIC_GROUP_BY_ITEM_RESOLUTION_NODE("msr"),
    METRIC_GROUP_BY_ITEM_RESOLUTION_NODE("mtr"),
    VALUES_GROUP_BY_ITEM_RESOLUTION_NODE("vr"),

    DATAFLOW_PLAN_PREFIX("dfp"),
    DATAFLOW_PLAN_SUBGRAPH_PREFIX("dfpsub"),
    OPTIMIZED_DATAFLOW_PLAN_PREFIX("dfpo"),
    SQL_PLAN_PREFIX("sqp"),
    EXEC_PLAN_PREFIX("ep"),

    MF_DAG("mfd"),

    TIME_SPINE_SOURCE("time_spine_src"),
    SUB_QUERY("subq"),
    CTE("cte"),
    NODE_RESOLVER_SUB_QUERY("nr_subq"),
}
