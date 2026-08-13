package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.ConstantPropertySpec
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.TimeWindow

/**
 * Builds a dataset of successful conversion events.
 *
 * Port of `metricflow.dataflow.nodes.join_conversion_events.JoinConversionEventsNode`.
 *
 * @property baseNode Node containing the dataset for computing base events.
 * @property baseTimeDimensionSpec Time dimension for the base events to compute against.
 * @property conversionNode Node containing the dataset to join base node for computing conversions.
 * @property conversionInputMetricSpec Simple-metric input exposed in the resulting dataset for aggregation.
 * @property conversionTimeDimensionSpec Time dimension for the conversion events.
 * @property uniqueIdentifierKeys Columns that uniquely identify each conversion event.
 * @property entitySpec The specific entity the conversion is happening for.
 * @property window Time-range bound for when a conversion is still considered valid (`null` = INF).
 * @property constantProperties Optional set of dimension/entity elements to join base→conversion.
 */
class JoinConversionEventsNode(
    val baseNode: DataflowPlanNode,
    val baseTimeDimensionSpec: TimeDimensionSpec,
    val conversionNode: DataflowPlanNode,
    val conversionInputMetricSpec: SimpleMetricInputSpec,
    val conversionTimeDimensionSpec: TimeDimensionSpec,
    val uniqueIdentifierKeys: List<InstanceSpec>,
    val entitySpec: EntitySpec,
    val window: TimeWindow?,
    val constantProperties: List<ConstantPropertySpec>?,
) : DataflowPlanNode(parentNodes = listOf(baseNode, conversionNode)) {

    override val description: String
        get() {
            val rangeDesc = window?.let { "${it.count} ${it.granularity}" } ?: "INF"
            return "Find conversions for ${entitySpec.dunderName} within the range of $rangeDesc"
        }

    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_JOIN_CONVERSION_EVENTS_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            addAll(super.displayedProperties)
            add(DisplayedProperty("base_time_dimension_spec", baseTimeDimensionSpec))
            add(DisplayedProperty("conversion_time_dimension_spec", conversionTimeDimensionSpec))
            add(DisplayedProperty("entity_spec", entitySpec))
            add(DisplayedProperty("window", window))
            for (uniqueSpec in uniqueIdentifierKeys) add(DisplayedProperty("unique_key_specs", uniqueSpec))
            for (prop in constantProperties.orEmpty()) add(DisplayedProperty("constant_property", prop))
        }

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitJoinConversionEventsNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean {
        if (other !is JoinConversionEventsNode) return false
        return other.baseTimeDimensionSpec == baseTimeDimensionSpec &&
            other.conversionTimeDimensionSpec == conversionTimeDimensionSpec &&
            other.conversionInputMetricSpec == conversionInputMetricSpec &&
            other.uniqueIdentifierKeys == uniqueIdentifierKeys &&
            other.entitySpec == entitySpec &&
            other.window == window &&
            other.constantProperties == constantProperties
    }

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): JoinConversionEventsNode {
        check(newParentNodes.size == 2) {
            "JoinConversionEventsNode expects exactly two parents (base + conversion). Got: ${newParentNodes.size}"
        }
        return JoinConversionEventsNode(
            baseNode = newParentNodes[0],
            baseTimeDimensionSpec = baseTimeDimensionSpec,
            conversionNode = newParentNodes[1],
            conversionInputMetricSpec = conversionInputMetricSpec,
            conversionTimeDimensionSpec = conversionTimeDimensionSpec,
            uniqueIdentifierKeys = uniqueIdentifierKeys,
            entitySpec = entitySpec,
            window = window,
            constantProperties = constantProperties,
        )
    }
}
