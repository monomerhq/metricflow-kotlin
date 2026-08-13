package cc.monomer.metricflow.domain.query.naming

/**
 * Where an input string sits inside a query.
 *
 * Port of `metricflow_semantic_interfaces.parsing.where_filter.parameter_set_factory.QueryItemLocation`.
 *
 * Some object-builder syntaxes are allowed in `ORDER_BY` clauses but not in
 * a `WHERE` filter (e.g. the `descending` argument) — the parser threads
 * this enum through so each call site can apply the right grammar.
 */
enum class QueryItemLocation(val value: String) {
    ORDER_BY("order_by"),
    NON_ORDER_BY("non_order_by"),
}
