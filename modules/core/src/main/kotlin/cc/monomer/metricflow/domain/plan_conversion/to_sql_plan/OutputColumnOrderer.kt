package cc.monomer.metricflow.domain.plan_conversion.to_sql_plan

import cc.monomer.metricflow.domain.spec.InputSpecOrder
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn

/**
 * Decides the order of SELECT columns in the rendered output.
 *
 * Port of `metricflow.plan_conversion.to_sql_plan.output_column_orderer.OutputColumnOrderer`.
 *
 * Concrete implementations either group columns by spec-type (the engine default) or preserve
 * the order in which group-by items / metrics were supplied in the original query input.
 */
interface OutputColumnOrderer {
    /** Return the SELECT columns in their desired output order for the supplied spec-mapping. */
    fun orderColumns(
        specToColumnsMapping: Map<InstanceSpec, List<SqlSelectColumn>>,
    ): List<SqlSelectColumn>
}

/**
 * Group columns by spec type, in the same canonical order as
 * [cc.monomer.metricflow.domain.plan_conversion.helpers.SelectColumnSet.columnsInDefaultOrder].
 *
 * Port of `TypeGroupedOrderer`.
 */
class TypeGroupedOrderer : OutputColumnOrderer {
    override fun orderColumns(
        specToColumnsMapping: Map<InstanceSpec, List<SqlSelectColumn>>,
    ): List<SqlSelectColumn> {
        val orderedSpecs = specsInTypeGroupOrder(specToColumnsMapping.keys.toList())
        return orderedSpecs.flatMap { spec -> specToColumnsMapping.getValue(spec) }
    }
}

/**
 * Group columns by spec type while preserving the relative order from the query input within
 * each group.
 *
 * Port of `InputOrderPreservingTypeGroupedOrderer`. Falls back to [TypeGroupedOrderer] when
 * the input order does not exactly describe the available specs.
 */
class InputOrderPreservingTypeGroupedOrderer(
    private val inputSpecOrder: InputSpecOrder,
) : OutputColumnOrderer {

    override fun orderColumns(
        specToColumnsMapping: Map<InstanceSpec, List<SqlSelectColumn>>,
    ): List<SqlSelectColumn> {
        val validation = validateInputSpecOrder(inputSpecOrder, specToColumnsMapping)
        if (validation.hasIssues) return TypeGroupedOrderer().orderColumns(specToColumnsMapping)

        val orderedSpecs = specsInInputOrder(inputSpecOrder)
        val specToGroupOrder = orderedSpecs.withIndex().associate { (idx, spec) -> spec to idx }

        return specsInTypeGroupOrder(orderedSpecs).sortedBy { specToGroupOrder.getValue(it) }
            .flatMap { specToColumnsMapping.getValue(it) }
    }
}

/**
 * Return columns in the exact order specs were supplied in the query input.
 *
 * Port of `InputOrderPreservingOrderer`. Falls back to [TypeGroupedOrderer] when the input
 * order does not exactly describe the available specs.
 */
class InputOrderPreservingOrderer(
    private val inputSpecOrder: InputSpecOrder,
) : OutputColumnOrderer {

    override fun orderColumns(
        specToColumnsMapping: Map<InstanceSpec, List<SqlSelectColumn>>,
    ): List<SqlSelectColumn> {
        val validation = validateInputSpecOrder(inputSpecOrder, specToColumnsMapping)
        if (validation.hasIssues) return TypeGroupedOrderer().orderColumns(specToColumnsMapping)
        return specsInInputOrder(inputSpecOrder).flatMap { specToColumnsMapping.getValue(it) }
    }
}

// -- internal helpers ---------------------------------------------------------------------------

private fun specsInInputOrder(inputSpecOrder: InputSpecOrder): List<InstanceSpec> =
    inputSpecOrder.groupByItemSpecs + inputSpecOrder.metricSpecs

private fun specsInTypeGroupOrder(specs: List<InstanceSpec>): List<InstanceSpec> {
    val grouped = InstanceSpecSet.createFromSpecs(specs)
    return grouped.timeDimensionSpecs +
        grouped.entitySpecs +
        grouped.dimensionSpecs +
        grouped.groupByMetricSpecs +
        grouped.metricSpecs +
        grouped.simpleMetricInputSpecs +
        grouped.metadataSpecs
}

private data class InputSpecOrderValidation(
    val unknownSpecs: List<InstanceSpec>,
    val unaccountedSpecs: List<InstanceSpec>,
) {
    val hasIssues: Boolean get() = unknownSpecs.isNotEmpty() || unaccountedSpecs.isNotEmpty()
}

private fun validateInputSpecOrder(
    inputSpecOrder: InputSpecOrder,
    specToColumnsMapping: Map<InstanceSpec, List<SqlSelectColumn>>,
): InputSpecOrderValidation {
    val accounted = LinkedHashSet<InstanceSpec>()
    val unknown = LinkedHashSet<InstanceSpec>()

    for (spec in specsInInputOrder(inputSpecOrder)) {
        if (!specToColumnsMapping.containsKey(spec)) unknown.add(spec) else accounted.add(spec)
    }

    val unaccounted = LinkedHashSet<InstanceSpec>()
    if (accounted.size != specToColumnsMapping.size) {
        for (spec in specToColumnsMapping.keys) if (spec !in accounted) unaccounted.add(spec)
    }

    return InputSpecOrderValidation(
        unknownSpecs = unknown.toList(),
        unaccountedSpecs = unaccounted.toList(),
    )
}
