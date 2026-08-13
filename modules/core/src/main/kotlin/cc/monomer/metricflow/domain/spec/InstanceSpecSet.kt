package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.util.Mergeable

/**
 * Container that bins [InstanceSpec]s by concrete variant.
 *
 * Port of `metricflow_semantics.specs.spec_set.InstanceSpecSet`.
 *
 * Used pervasively by the dataflow planner so each pass can iterate over
 * specs of a single type without re-grouping. The set is [Mergeable]; merge
 * is concatenation, dedup is a separate operation (see [dedupe]).
 */
data class InstanceSpecSet(
    val metricSpecs: List<MetricSpec>,
    val simpleMetricInputSpecs: List<SimpleMetricInputSpec>,
    val dimensionSpecs: List<DimensionSpec>,
    val entitySpecs: List<EntitySpec>,
    val timeDimensionSpecs: List<TimeDimensionSpec>,
    val groupByMetricSpecs: List<GroupByMetricSpec>,
    val metadataSpecs: List<MetadataSpec>,
) : Mergeable<InstanceSpecSet> {

    override fun merge(other: InstanceSpecSet): InstanceSpecSet = InstanceSpecSet(
        metricSpecs = metricSpecs + other.metricSpecs,
        simpleMetricInputSpecs = simpleMetricInputSpecs + other.simpleMetricInputSpecs,
        dimensionSpecs = dimensionSpecs + other.dimensionSpecs,
        entitySpecs = entitySpecs + other.entitySpecs,
        groupByMetricSpecs = groupByMetricSpecs + other.groupByMetricSpecs,
        timeDimensionSpecs = timeDimensionSpecs + other.timeDimensionSpecs,
        metadataSpecs = metadataSpecs + other.metadataSpecs,
    )

    /** Remove duplicates within each list, preserving order. */
    fun dedupe(): InstanceSpecSet = InstanceSpecSet(
        metricSpecs = metricSpecs.distinctPreservingOrder(),
        simpleMetricInputSpecs = simpleMetricInputSpecs.distinctPreservingOrder(),
        dimensionSpecs = dimensionSpecs.distinctPreservingOrder(),
        entitySpecs = entitySpecs.distinctPreservingOrder(),
        timeDimensionSpecs = timeDimensionSpecs.distinctPreservingOrder(),
        groupByMetricSpecs = groupByMetricSpecs.distinctPreservingOrder(),
        metadataSpecs = metadataSpecs.distinctPreservingOrder(),
    )

    /** All linkable specs in iteration order: dim → time-dim → entity → group-by-metric. */
    val linkableSpecs: List<LinkableInstanceSpec>
        get() = dimensionSpecs + timeDimensionSpecs + entitySpecs + groupByMetricSpecs

    /** All specs (linkable + non-linkable + metrics + metadata) in iteration order. */
    val allSpecs: List<InstanceSpec>
        get() = simpleMetricInputSpecs + dimensionSpecs + timeDimensionSpecs +
            entitySpecs + groupByMetricSpecs + metricSpecs + metadataSpecs

    /** Time dimension specs that refer to `metric_time` at any grain. */
    val metricTimeSpecs: List<TimeDimensionSpec>
        get() = timeDimensionSpecs.filter { it.isMetricTime }

    /** Apply an [InstanceSpecSetTransform]. */
    fun <OutputT> transform(transform: InstanceSpecSetTransform<OutputT>): OutputT =
        transform.transform(this)

    private fun <T> List<T>.distinctPreservingOrder(): List<T> {
        val seen = LinkedHashSet<T>(size)
        for (item in this) seen.add(item)
        return seen.toList()
    }

    companion object {
        /** [Mergeable] identity element. */
        val EMPTY: InstanceSpecSet = InstanceSpecSet(
            metricSpecs = emptyList(),
            simpleMetricInputSpecs = emptyList(),
            dimensionSpecs = emptyList(),
            entitySpecs = emptyList(),
            timeDimensionSpecs = emptyList(),
            groupByMetricSpecs = emptyList(),
            metadataSpecs = emptyList(),
        )

        /** Group an iterable of [InstanceSpec]s into the appropriate buckets. */
        fun createFromSpecs(specs: Iterable<InstanceSpec>): InstanceSpecSet = groupSpecsByType(specs)
    }
}

/**
 * Apply a transformation over an [InstanceSpecSet].
 *
 * Port of `metricflow_semantics.specs.spec_set.InstanceSpecSetTransform`.
 */
interface InstanceSpecSetTransform<OutputT> {
    fun transform(specSet: InstanceSpecSet): OutputT
}

/** Group a sequence of specs into an [InstanceSpecSet] by variant type. */
fun groupSpecsByType(specs: Iterable<InstanceSpec>): InstanceSpecSet {
    val grouper = GroupSpecByTypeVisitor()
    for (spec in specs) spec.accept(grouper)
    return InstanceSpecSet(
        metricSpecs = grouper.metricSpecs.toList(),
        simpleMetricInputSpecs = grouper.simpleMetricInputSpecs.toList(),
        dimensionSpecs = grouper.dimensionSpecs.toList(),
        entitySpecs = grouper.entitySpecs.toList(),
        timeDimensionSpecs = grouper.timeDimensionSpecs.toList(),
        groupByMetricSpecs = grouper.groupByMetricSpecs.toList(),
        metadataSpecs = grouper.metadataSpecs.toList(),
    )
}

/** Group a single spec into a singleton [InstanceSpecSet]. */
fun groupSpecByType(spec: InstanceSpec): InstanceSpecSet = groupSpecsByType(listOf(spec))

private class GroupSpecByTypeVisitor : InstanceSpecVisitor<Unit> {
    val metricSpecs = mutableListOf<MetricSpec>()
    val simpleMetricInputSpecs = mutableListOf<SimpleMetricInputSpec>()
    val dimensionSpecs = mutableListOf<DimensionSpec>()
    val entitySpecs = mutableListOf<EntitySpec>()
    val timeDimensionSpecs = mutableListOf<TimeDimensionSpec>()
    val groupByMetricSpecs = mutableListOf<GroupByMetricSpec>()
    val metadataSpecs = mutableListOf<MetadataSpec>()

    override fun visitSimpleMetricInputSpec(spec: SimpleMetricInputSpec) {
        simpleMetricInputSpecs.add(spec)
    }
    override fun visitDimensionSpec(spec: DimensionSpec) { dimensionSpecs.add(spec) }
    override fun visitTimeDimensionSpec(spec: TimeDimensionSpec) { timeDimensionSpecs.add(spec) }
    override fun visitEntitySpec(spec: EntitySpec) { entitySpecs.add(spec) }
    override fun visitGroupByMetricSpec(spec: GroupByMetricSpec) { groupByMetricSpecs.add(spec) }
    override fun visitMetricSpec(spec: MetricSpec) { metricSpecs.add(spec) }
    override fun visitMetadataSpec(spec: MetadataSpec) { metadataSpecs.add(spec) }
}
