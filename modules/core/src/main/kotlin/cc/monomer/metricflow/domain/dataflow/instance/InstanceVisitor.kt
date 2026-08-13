package cc.monomer.metricflow.domain.dataflow.instance

/**
 * Visitor over the [MdoInstance] hierarchy — port of `metricflow_semantics.instances.InstanceVisitor`.
 *
 * Like the [cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor], the closed
 * variant set is enforced via the Kotlin interface: adding a new instance variant without
 * extending this interface produces a compile error at every visitor implementation site.
 */
interface InstanceVisitor<R> {
    fun visitSimpleMetricInputInstance(instance: SimpleMetricInputInstance): R
    fun visitDimensionInstance(instance: DimensionInstance): R
    fun visitTimeDimensionInstance(instance: TimeDimensionInstance): R
    fun visitEntityInstance(instance: EntityInstance): R
    fun visitGroupByMetricInstance(instance: GroupByMetricInstance): R
    fun visitMetricInstance(instance: MetricInstance): R
    fun visitMetadataInstance(instance: MetadataInstance): R
}
