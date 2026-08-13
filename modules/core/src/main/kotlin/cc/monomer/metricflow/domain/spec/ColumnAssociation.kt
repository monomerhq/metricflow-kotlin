package cc.monomer.metricflow.domain.spec

/**
 * Marker interface for a key object used to correlate columns between
 * instance sets.
 *
 * Port of `metricflow_semantics.specs.column_assoc.ColumnCorrelationKey`.
 *
 * Two columns flowing through different stages of the dataflow plan can be
 * tied together by an equality match on their correlation keys. Today only
 * [SingleColumnCorrelationKey] is used, but the interface is preserved to
 * keep room for multi-column correlations (matching the Python design).
 */
sealed interface ColumnCorrelationKey

/**
 * Key to use when there's only one column association in an instance.
 *
 * Port of `metricflow_semantics.specs.column_assoc.SingleColumnCorrelationKey`.
 *
 * It is intentionally a Kotlin `object` (singleton) — all instances are
 * interchangeable. Python's class equates instances by `isinstance`, which is
 * what `object` gives us for free.
 */
data object SingleColumnCorrelationKey : ColumnCorrelationKey

/**
 * Describes how an instance is associated with a column in a table / SQL
 * query.
 *
 * Port of `metricflow_semantics.specs.column_assoc.ColumnAssociation`.
 *
 * Comparing this key tells the planner which input column corresponds to
 * which output column as data flows from one node to the next.
 */
data class ColumnAssociation(
    val columnName: String,
    val singleColumnCorrelationKey: SingleColumnCorrelationKey,
) {
    /** Convenience accessor matching the Python `column_correlation_key` property. */
    val columnCorrelationKey: ColumnCorrelationKey get() = singleColumnCorrelationKey

    companion object {
        /** Construct a single-column association from just a column name. */
        fun ofSingle(columnName: String): ColumnAssociation =
            ColumnAssociation(columnName, SingleColumnCorrelationKey)
    }
}

/**
 * Strategy for naming the column associated with a given [InstanceSpec].
 *
 * Port of `metricflow_semantics.specs.column_assoc.ColumnAssociationResolver`.
 *
 * For example, dimensions with links are named `<entity_link>__<name>`,
 * time dimensions append the granularity, etc. Centralising the naming logic
 * keeps the produced SQL grep-able and lets users write WHERE constraints in
 * SQL assuming this format.
 */
interface ColumnAssociationResolver {
    /** Resolve the column association for the supplied [spec]. */
    fun resolveSpec(spec: InstanceSpec): ColumnAssociation

    /**
     * Return a copy that has the `dunderPrefixSimpleMetricInputs` option set.
     *
     * The option controls whether simple-metric inputs are resolved with a
     * leading `__` prefix (e.g. `__bookings`).
     */
    fun withOptions(dunderPrefixSimpleMetricInputs: Boolean): ColumnAssociationResolver
}
