package cc.monomer.metricflow.domain.spec

/**
 * A spec for an ORDER BY column in a query.
 *
 * Port of `metricflow_semantics.specs.order_by_spec.OrderBySpec`.
 *
 * Wraps any [InstanceSpec] plus a descending flag — the spec identifies the
 * column, and the flag picks ASC vs DESC.
 */
data class OrderBySpec(
    val instanceSpec: InstanceSpec,
    val descending: Boolean,
) {
    /** Return an order-by spec with the spec's alias replaced. */
    fun withAlias(alias: String?): OrderBySpec =
        OrderBySpec(instanceSpec = instanceSpec.withAlias(alias), descending = descending)
}
