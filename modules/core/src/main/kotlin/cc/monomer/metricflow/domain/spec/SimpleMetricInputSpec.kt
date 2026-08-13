package cc.monomer.metricflow.domain.spec

/**
 * A spec for a simple-metric input column.
 *
 * Port of `metricflow_semantics.specs.simple_metric_input_spec.SimpleMetricInputSpec`.
 *
 * "Simple metrics" are the leaf metrics that derive directly from a single
 * semantic-model measure (i.e. the old "measure" concept post-renaming). The
 * spec describes the input column to a simple metric, including the optional
 * [fillNullsWith] used for null-coalescing aggregations.
 */
data class SimpleMetricInputSpec(
    override val elementName: String,
    val fillNullsWith: Int?,
) : InstanceSpec {

    override val dunderName: String get() = elementName

    override fun <OutputT> accept(visitor: InstanceSpecVisitor<OutputT>): OutputT =
        visitor.visitSimpleMetricInputSpec(this)
}
