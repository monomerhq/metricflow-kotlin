package cc.monomer.metricflow.domain.spec

/**
 * A specification for an instance of a metric definition object.
 *
 * Port of `metricflow_semantics.specs.instance_spec.InstanceSpec`.
 *
 * An "instance" of a metric/dimension/entity describes a *column* flowing
 * through a [dataflow plan][cc.monomer.metricflow.domain.dataflow].
 * The same logical element (`bookings`, `metric_time`) can appear with
 * different states across nodes — for example, a time dimension at varying
 * grains. The spec captures every attribute the plan needs to label that
 * column.
 *
 * Sub-hierarchy:
 * - [LinkableInstanceSpec] — has [entityLinks]: dimension, time-dimension,
 *   entity, group-by-metric.
 * - Non-linkable: [MetricSpec], [MeasureLikeSpec][SimpleMetricInputSpec],
 *   [MetadataSpec].
 *
 * The visitor pattern is preserved verbatim via [accept]/[InstanceSpecVisitor]
 * so that downstream waves can port the Python visitors mechanically.
 */
sealed interface InstanceSpec {
    /** Name of the dimension or entity in the semantic model. */
    val elementName: String

    /** Return the dunder name of this spec. e.g. `user_id__country`. */
    val dunderName: String

    /** Dispatch to a visitor. */
    fun <OutputT> accept(visitor: InstanceSpecVisitor<OutputT>): OutputT

    /** Return the instance spec without any filtering (for comparison purposes). */
    fun withoutFilterSpecs(): InstanceSpec = this

    /** Return the instance spec with the alias replaced. Default is identity. */
    fun withAlias(alias: String?): InstanceSpec = this

    companion object {
        /** Merge all spec lists into a single list, preserving order. */
        fun merge(vararg specs: Iterable<InstanceSpec>): List<InstanceSpec> {
            val result = mutableListOf<InstanceSpec>()
            for (s in specs) result.addAll(s)
            return result
        }
    }
}

/**
 * Visitor for [InstanceSpec] subtypes.
 *
 * Port of `metricflow_semantics.specs.instance_spec.InstanceSpecVisitor`.
 *
 * Implementations cover every concrete spec variant. Sealed-interface
 * exhaustiveness lets the Kotlin compiler verify completeness even though
 * dispatch happens through the [InstanceSpec.accept] method.
 */
interface InstanceSpecVisitor<OutputT> {
    fun visitSimpleMetricInputSpec(spec: SimpleMetricInputSpec): OutputT
    fun visitDimensionSpec(spec: DimensionSpec): OutputT
    fun visitTimeDimensionSpec(spec: TimeDimensionSpec): OutputT
    fun visitEntitySpec(spec: EntitySpec): OutputT
    fun visitGroupByMetricSpec(spec: GroupByMetricSpec): OutputT
    fun visitMetricSpec(spec: MetricSpec): OutputT
    fun visitMetadataSpec(spec: MetadataSpec): OutputT
}
