package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel
import cc.monomer.metricflow.common.graph.MetricFlowGraphNode
import cc.monomer.metricflow.common.graph.MetricFlowGraphNodeDescriptor
import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStep
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStepProvider
import cc.monomer.metricflow.domain.semantic_graph.node.CategoricalDimensionAttributeNodeKind
import cc.monomer.metricflow.domain.semantic_graph.node.ComplexMetricLabel
import cc.monomer.metricflow.domain.semantic_graph.node.ConfiguredEntityLabel
import cc.monomer.metricflow.domain.semantic_graph.node.GroupByAttributeLabel
import cc.monomer.metricflow.domain.semantic_graph.node.JoinedModelLabel
import cc.monomer.metricflow.domain.semantic_graph.node.KeyAttributeLabel
import cc.monomer.metricflow.domain.semantic_graph.node.KeyAttributeNodeKind
import cc.monomer.metricflow.domain.semantic_graph.node.LocalModelLabel
import cc.monomer.metricflow.domain.semantic_graph.node.MetricLabel
import cc.monomer.metricflow.domain.semantic_graph.node.MetricTimeLabel
import cc.monomer.metricflow.domain.semantic_graph.node.SimpleMetricLabel
import cc.monomer.metricflow.domain.semantic_graph.node.TimeAttributeNodeKind
import cc.monomer.metricflow.domain.semantic_graph.node.TimeClusterLabel
import cc.monomer.metricflow.domain.semantic_graph.node.TimeDimensionLabel

/**
 * A node in the semantic graph.
 *
 * Port of `metricflow_semantics/semantic_graph/sg_interfaces.py::SemanticGraphNode`
 * combined with the concrete variants in `nodes/entity_nodes.py` and
 * `nodes/attribute_nodes.py`.
 *
 * Two top-level kinds:
 *
 * - **Entity nodes** ([ConfiguredEntityNode], [JoinedModelNode], [LocalModelNode],
 *   [SimpleMetricNode], [ComplexMetricNode], [TimeDimensionNode], [MetricTimeNode],
 *   [TimeNode]) — have successors and represent something with successors.
 * - **Attribute nodes** ([TimeAttributeNode], [KeyAttributeNode], [CategoricalDimensionAttributeNode])
 *   — leaves; the recipe terminates at one.
 *
 * Every node returns a [recipeStepToAppend] describing how the attribute recipe
 * is updated when the path-finder traverses the node.
 */
sealed class SemanticGraphNode :
    MetricFlowGraphNode(),
    AttributeRecipeStepProvider {

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            for (prop in recipeStepToAppend.displayedProperties) add(prop)
            for (label in labels) add(DisplayedProperty("label", label.toString()))
        }

    /**
     * The dunder-name element associated with this node, when one exists.
     *
     * Not every node has a direct relationship to the dunder name, but many
     * do (e.g. the metric-time entity corresponds to `metric_time`).
     */
    open val dunderNameElement: String? get() = recipeStepToAppend.addDunderNameElement
}

// --- Entity nodes ------------------------------------------------------------

/**
 * Represents an `entity` element as configured in a semantic model.
 *
 * Port of `ConfiguredEntityNode`. Named "configured" to avoid confusion
 * between entities in the semantic manifest and entities in the semantic graph.
 */
data class ConfiguredEntityNode(
    val entityName: String,
    val modelId: SemanticModelId,
) : SemanticGraphNode() {

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "${modelId.modelName}.$entityName",
            clusterName = ClusterNameFactory.CONFIGURED_ENTITY,
        )

    override val labels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet(listOf(ConfiguredEntityLabel))

    override val recipeStepToAppend: AttributeRecipeStep = AttributeRecipeStep.EMPTY.copy(
        addEntityLink = entityName,
        addDunderNameElement = entityName,
    )

    companion object {
        fun getInstance(entityName: String, modelId: SemanticModelId): ConfiguredEntityNode =
            ConfiguredEntityNode(entityName, modelId)
    }
}

/**
 * The semantic-model node used when accessed via an entity link.
 *
 * Port of `JoinedModelNode`. See `LocalModelNode` for the no-link counterpart.
 */
data class JoinedModelNode(val modelId: SemanticModelId) : SemanticGraphNode() {

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "JoinedModel(${modelId.modelName})",
            clusterName = ClusterNameFactory.getNameForModel(modelId),
        )

    override val labels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet(listOf(JoinedModelLabel))

    companion object {
        fun getInstance(modelId: SemanticModelId): JoinedModelNode = JoinedModelNode(modelId)
    }
}

/**
 * The semantic-model node used when accessed without any entity link.
 *
 * Port of `LocalModelNode`. The local node carries an `addModelJoin` recipe
 * step that records the originating model.
 */
data class LocalModelNode(val modelId: SemanticModelId) : SemanticGraphNode() {

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "LocalModel(${modelId.modelName})",
            clusterName = ClusterNameFactory.getNameForModel(modelId),
        )

    override val labels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet(listOf(LocalModelLabel))

    override val recipeStepToAppend: AttributeRecipeStep = AttributeRecipeStep.EMPTY.copy(
        addModelJoin = modelId,
    )

    companion object {
        fun getInstance(modelId: SemanticModelId): LocalModelNode = LocalModelNode(modelId)
    }
}

/**
 * A time-dimension entity node.
 *
 * Port of `TimeDimensionNode`. Time dimensions are represented as entities
 * that connect to the [TimeNode] (which then connects to grain attributes).
 */
data class TimeDimensionNode(val dimensionName: String) : SemanticGraphNode() {

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "TimeDimension($dimensionName)",
            clusterName = ClusterNameFactory.TIME_DIMENSION,
        )

    override val labels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet(listOf(TimeDimensionLabel))

    override val recipeStepToAppend: AttributeRecipeStep = AttributeRecipeStep.EMPTY.copy(
        addDunderNameElement = dimensionName,
        setElementType = LinkableElementType.TIME_DIMENSION,
    )

    companion object {
        fun getInstance(dimensionName: String): TimeDimensionNode = TimeDimensionNode(dimensionName)
    }
}

/**
 * The node that represents `metric_time`.
 *
 * Port of `MetricTimeNode`.
 */
data object MetricTimeNode : SemanticGraphNode() {

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "MetricTime",
            clusterName = ClusterNameFactory.TIME_DIMENSION,
        )

    override val labels: OrderedSet<MetricFlowGraphLabel> =
        FrozenOrderedSet(listOf(MetricTimeLabel, TimeDimensionLabel))

    override val recipeStepToAppend: AttributeRecipeStep = AttributeRecipeStep.EMPTY.copy(
        addDunderNameElement = METRIC_TIME_ELEMENT_NAME,
        addProperties = listOf(GroupByItemProperty.METRIC_TIME),
        setElementType = LinkableElementType.TIME_DIMENSION,
    )

    /** Python parity: factory returning the singleton. */
    fun getInstance(): MetricTimeNode = this
}

/**
 * An entity representing time itself.
 *
 * Port of `TimeNode`. Other time-related entities (time dimensions,
 * `metric_time`) connect into this node, which fans out to per-grain
 * [TimeAttributeNode]s.
 */
data object TimeNode : SemanticGraphNode() {

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "TimeEntity",
            clusterName = ClusterNameFactory.TIME,
        )

    override val labels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet(listOf(TimeClusterLabel))

    fun getInstance(): TimeNode = this
}

/** Marker for any metric-style node. */
sealed class MetricNode(val metricName: String) : SemanticGraphNode() {
    override val labels: OrderedSet<MetricFlowGraphLabel>
        get() = FrozenOrderedSet(listOf(MetricLabel(null), MetricLabel(metricName)))
}

/** Represents a simple metric. */
class SimpleMetricNode(metricName: String) : MetricNode(metricName) {

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "SimpleMetric($metricName)",
            clusterName = ClusterNameFactory.METRIC,
        )

    override val labels: OrderedSet<MetricFlowGraphLabel>
        get() = super.labels.union(listOf(SimpleMetricLabel))

    override fun equals(other: Any?): Boolean = other is SimpleMetricNode && metricName == other.metricName
    override fun hashCode(): Int = metricName.hashCode() xor 0x5a31d2e1

    companion object {
        fun getInstance(metricName: String): SimpleMetricNode = SimpleMetricNode(metricName)
    }
}

/** Represents metrics that are defined from other metrics (ratio, cumulative, conversion, derived). */
class ComplexMetricNode(metricName: String) : MetricNode(metricName) {

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "ComplexMetric($metricName)",
            clusterName = ClusterNameFactory.METRIC,
        )

    override val labels: OrderedSet<MetricFlowGraphLabel>
        get() = super.labels.union(listOf(ComplexMetricLabel))

    override fun equals(other: Any?): Boolean = other is ComplexMetricNode && metricName == other.metricName
    override fun hashCode(): Int = metricName.hashCode() xor 0x6c40b819

    companion object {
        fun getInstance(metricName: String): ComplexMetricNode = ComplexMetricNode(metricName)
    }
}

// --- Attribute nodes ---------------------------------------------------------

/** Marker base for attribute leaves. */
sealed class AttributeNode(val attributeName: String) : SemanticGraphNode() {

    override val labels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet(listOf(GroupByAttributeLabel))

    override val recipeStepToAppend: AttributeRecipeStep
        get() = AttributeRecipeStep.EMPTY.copy(addDunderNameElement = attributeName)
}

/**
 * Attribute node for the different time grains available for time-dimension querying.
 *
 * Port of `TimeAttributeNode`. Carries either:
 *
 * - a standard grain (constructed via [TimeAttributeNodeKind.STANDARD_GRAIN]),
 * - a date-part suffix (constructed via [TimeAttributeNodeKind.DATE_PART]), or
 * - a custom expanded grain (constructed via [TimeAttributeNodeKind.EXPANDED_GRAIN]).
 */
class TimeAttributeNode private constructor(
    attributeName: String,
    val kind: TimeAttributeNodeKind,
    val elementPropertyAdditions: List<GroupByItemProperty>,
    val grain: TimeGranularity?,
    val datePart: DatePart?,
    val expandedGrain: ExpandedTimeGranularity?,
) : AttributeNode(attributeName) {

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "TimeAttribute($attributeName)",
            clusterName = ClusterNameFactory.TIME,
        )

    override val labels: OrderedSet<MetricFlowGraphLabel>
        get() = super.labels.union(listOf(TimeClusterLabel))

    override val recipeStepToAppend: AttributeRecipeStep
        get() = AttributeRecipeStep.EMPTY.copy(
            addDunderNameElement = attributeName,
            addProperties = if (elementPropertyAdditions.isEmpty()) null else elementPropertyAdditions,
            setElementType = LinkableElementType.TIME_DIMENSION,
        )

    override fun equals(other: Any?): Boolean = other is TimeAttributeNode &&
        attributeName == other.attributeName && kind == other.kind
    override fun hashCode(): Int = attributeName.hashCode() * 31 + kind.hashCode()

    companion object {
        /** Construct a node for a standard time grain. */
        fun getInstanceForTimeGrain(timeGrain: TimeGranularity): TimeAttributeNode =
            TimeAttributeNode(
                attributeName = timeGrain.value,
                kind = TimeAttributeNodeKind.STANDARD_GRAIN,
                elementPropertyAdditions = emptyList(),
                grain = timeGrain,
                datePart = null,
                expandedGrain = null,
            )

        /** Construct a node for a date_part. */
        fun getInstanceForDatePart(datePart: DatePart): TimeAttributeNode =
            TimeAttributeNode(
                attributeName = "extract_${datePart.value}",
                kind = TimeAttributeNodeKind.DATE_PART,
                elementPropertyAdditions = listOf(GroupByItemProperty.DATE_PART),
                grain = null,
                datePart = datePart,
                expandedGrain = null,
            )

        /** Construct a node for an expanded (custom) grain. */
        fun getInstanceForExpandedTimeGrain(grain: ExpandedTimeGranularity): TimeAttributeNode =
            TimeAttributeNode(
                attributeName = grain.name,
                kind = TimeAttributeNodeKind.EXPANDED_GRAIN,
                elementPropertyAdditions = listOf(GroupByItemProperty.DERIVED_TIME_GRANULARITY),
                grain = null,
                datePart = null,
                expandedGrain = grain,
            )
    }
}

/**
 * Attribute node representing the entity-key value.
 *
 * Port of `KeyAttributeNode`. "Querying an entity" in MF means querying the
 * values of the configured entity column.
 */
class KeyAttributeNode(attributeName: String) : AttributeNode(attributeName) {

    val kind: KeyAttributeNodeKind = KeyAttributeNodeKind.ENTITY_KEY

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "KeyAttribute($attributeName)",
            clusterName = ClusterNameFactory.KEY,
        )

    override val labels: OrderedSet<MetricFlowGraphLabel>
        get() = super.labels.union(listOf(KeyAttributeLabel))

    override val recipeStepToAppend: AttributeRecipeStep
        get() = AttributeRecipeStep.EMPTY.copy(
            addDunderNameElement = attributeName,
            addProperties = listOf(GroupByItemProperty.ENTITY),
            setElementType = LinkableElementType.ENTITY,
        )

    override fun equals(other: Any?): Boolean = other is KeyAttributeNode && attributeName == other.attributeName
    override fun hashCode(): Int = attributeName.hashCode() xor 0x12345678

    companion object {
        fun getInstance(entityName: String): KeyAttributeNode = KeyAttributeNode(entityName)
    }
}

/**
 * Attribute node for a categorical dimension.
 *
 * Port of `CategoricalDimensionAttributeNode`.
 */
class CategoricalDimensionAttributeNode(attributeName: String) : AttributeNode(attributeName) {

    val kind: CategoricalDimensionAttributeNodeKind = CategoricalDimensionAttributeNodeKind.CATEGORICAL

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(
            nodeName = "Dimension($attributeName)",
            clusterName = ClusterNameFactory.DIMENSION,
        )

    override val recipeStepToAppend: AttributeRecipeStep
        get() = AttributeRecipeStep.EMPTY.copy(
            addDunderNameElement = attributeName,
            setElementType = LinkableElementType.DIMENSION,
        )

    override fun equals(other: Any?): Boolean = other is CategoricalDimensionAttributeNode &&
        attributeName == other.attributeName
    override fun hashCode(): Int = attributeName.hashCode() xor 0x36ad7c92

    companion object {
        fun getInstance(dimensionName: String): CategoricalDimensionAttributeNode =
            CategoricalDimensionAttributeNode(dimensionName)
    }
}
