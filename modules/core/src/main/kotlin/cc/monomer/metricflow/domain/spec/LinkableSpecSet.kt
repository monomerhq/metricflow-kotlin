package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.util.Mergeable

/**
 * Container that bins [LinkableInstanceSpec]s by concrete variant.
 *
 * Port of `metricflow_semantics.specs.linkable_spec_set.LinkableSpecSet`.
 *
 * Used everywhere group-by items need to be tracked across the dataflow
 * pipeline. Distinct from [InstanceSpecSet] in that this set holds only
 * linkable variants — there is no slot for metrics or metadata.
 */
data class LinkableSpecSet(
    val dimensionSpecs: List<DimensionSpec>,
    val timeDimensionSpecs: List<TimeDimensionSpec>,
    val entitySpecs: List<EntitySpec>,
    val groupByMetricSpecs: List<GroupByMetricSpec>,
) : Mergeable<LinkableSpecSet>, Collection<LinkableInstanceSpec> {

    /** True iff this set contains any spec referring to metric time at any grain. */
    val containsMetricTime: Boolean
        get() = metricTimeSpecs.isNotEmpty()

    /** Time dimension specs whose granularity is custom. */
    val timeDimensionSpecsWithCustomGrain: List<TimeDimensionSpec>
        get() = timeDimensionSpecs.filter { it.hasCustomGrain }

    /** Any time dimension specs referring to `metric_time`. */
    val metricTimeSpecs: List<TimeDimensionSpec>
        get() = timeDimensionSpecs.filter { it.isMetricTime }

    /** All linkable specs in iteration order: dim → time-dim → entity → group-by-metric. */
    val asTuple: List<LinkableInstanceSpec>
        get() = dimensionSpecs + timeDimensionSpecs + entitySpecs + groupByMetricSpecs

    /** View this set as the corresponding [InstanceSpecSet]. */
    val asInstanceSpecSet: InstanceSpecSet
        get() = InstanceSpecSet(
            metricSpecs = emptyList(),
            simpleMetricInputSpecs = emptyList(),
            dimensionSpecs = dimensionSpecs,
            entitySpecs = entitySpecs,
            timeDimensionSpecs = timeDimensionSpecs,
            groupByMetricSpecs = groupByMetricSpecs,
            metadataSpecs = emptyList(),
        )

    /** Strip aliases from every contained spec. */
    val withoutAliases: LinkableSpecSet
        get() = LinkableSpecSet(
            dimensionSpecs = dimensionSpecs.map { it.withAlias(null) },
            timeDimensionSpecs = timeDimensionSpecs.map { it.withAlias(null) },
            entitySpecs = entitySpecs.map { it.withAlias(null) },
            groupByMetricSpecs = groupByMetricSpecs.map { it.withAlias(null) },
        )

    /** Return a new set with the additional specs appended. */
    fun addSpecs(
        dimensionSpecs: List<DimensionSpec>,
        timeDimensionSpecs: List<TimeDimensionSpec>,
        entitySpecs: List<EntitySpec>,
        groupByMetricSpecs: List<GroupByMetricSpec>,
    ): LinkableSpecSet = LinkableSpecSet(
        dimensionSpecs = this.dimensionSpecs + dimensionSpecs,
        timeDimensionSpecs = this.timeDimensionSpecs + timeDimensionSpecs,
        entitySpecs = this.entitySpecs + entitySpecs,
        groupByMetricSpecs = this.groupByMetricSpecs + groupByMetricSpecs,
    )

    override fun merge(other: LinkableSpecSet): LinkableSpecSet = addSpecs(
        dimensionSpecs = other.dimensionSpecs,
        timeDimensionSpecs = other.timeDimensionSpecs,
        entitySpecs = other.entitySpecs,
        groupByMetricSpecs = other.groupByMetricSpecs,
    )

    /** Return a new set with duplicates removed, preserving order. */
    fun dedupe(): LinkableSpecSet = LinkableSpecSet(
        dimensionSpecs = dimensionSpecs.distinctPreservingOrder(),
        timeDimensionSpecs = timeDimensionSpecs.distinctPreservingOrder(),
        entitySpecs = entitySpecs.distinctPreservingOrder(),
        groupByMetricSpecs = groupByMetricSpecs.distinctPreservingOrder(),
    )

    /** Whether `this` is a subset of [other]. */
    fun isSubsetOf(other: LinkableSpecSet): Boolean = other.asTuple.toSet().containsAll(asTuple.toSet())

    /** Set difference (per bucket). */
    fun difference(other: LinkableSpecSet): LinkableSpecSet = LinkableSpecSet(
        dimensionSpecs = (dimensionSpecs.toSet() - other.dimensionSpecs.toSet()).toList(),
        timeDimensionSpecs = (timeDimensionSpecs.toSet() - other.timeDimensionSpecs.toSet()).toList(),
        entitySpecs = (entitySpecs.toSet() - other.entitySpecs.toSet()).toList(),
        groupByMetricSpecs = (groupByMetricSpecs.toSet() - other.groupByMetricSpecs.toSet()).toList(),
    )

    /** Replace every custom-granularity time dim spec with its base grain. */
    fun replaceCustomGranularityWithBaseGranularity(): LinkableSpecSet = LinkableSpecSet(
        dimensionSpecs = dimensionSpecs,
        timeDimensionSpecs = timeDimensionSpecs.map { it.withBaseGrain() },
        entitySpecs = entitySpecs,
        groupByMetricSpecs = groupByMetricSpecs,
    )

    override val size: Int get() = asTuple.size
    override fun isEmpty(): Boolean = asTuple.isEmpty()
    override fun iterator(): Iterator<LinkableInstanceSpec> = asTuple.iterator()
    override fun contains(element: LinkableInstanceSpec): Boolean = element in asTuple
    override fun containsAll(elements: Collection<LinkableInstanceSpec>): Boolean =
        asTuple.containsAll(elements)

    private fun <T> List<T>.distinctPreservingOrder(): List<T> {
        val seen = LinkedHashSet<T>(size)
        for (item in this) seen.add(item)
        return seen.toList()
    }

    companion object {
        /** [Mergeable] identity element. */
        val EMPTY: LinkableSpecSet = LinkableSpecSet(
            dimensionSpecs = emptyList(),
            timeDimensionSpecs = emptyList(),
            entitySpecs = emptyList(),
            groupByMetricSpecs = emptyList(),
        )

        /** Group an iterable of linkable specs by variant. */
        fun createFromSpecs(specs: Iterable<LinkableInstanceSpec>): LinkableSpecSet {
            val grouper = GroupLinkableSpecByTypeVisitor()
            for (spec in specs) spec.accept(grouper)
            return LinkableSpecSet(
                dimensionSpecs = grouper.dimensionSpecs.toList(),
                timeDimensionSpecs = grouper.timeDimensionSpecs.toList(),
                entitySpecs = grouper.entitySpecs.toList(),
                groupByMetricSpecs = grouper.groupByMetricSpecs.toList(),
            )
        }
    }
}

private class GroupLinkableSpecByTypeVisitor : InstanceSpecVisitor<Unit> {
    val dimensionSpecs = mutableListOf<DimensionSpec>()
    val entitySpecs = mutableListOf<EntitySpec>()
    val timeDimensionSpecs = mutableListOf<TimeDimensionSpec>()
    val groupByMetricSpecs = mutableListOf<GroupByMetricSpec>()

    override fun visitSimpleMetricInputSpec(spec: SimpleMetricInputSpec) {}
    override fun visitDimensionSpec(spec: DimensionSpec) { dimensionSpecs.add(spec) }
    override fun visitTimeDimensionSpec(spec: TimeDimensionSpec) { timeDimensionSpecs.add(spec) }
    override fun visitEntitySpec(spec: EntitySpec) { entitySpecs.add(spec) }
    override fun visitGroupByMetricSpec(spec: GroupByMetricSpec) { groupByMetricSpecs.add(spec) }
    override fun visitMetricSpec(spec: MetricSpec) {}
    override fun visitMetadataSpec(spec: MetadataSpec) {}
}
