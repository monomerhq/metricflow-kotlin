package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * A spec for a metric column.
 *
 * Port of `metricflow_semantics.specs.metric_spec.MetricSpec`.
 *
 * Metrics are not part of [LinkableInstanceSpec] because they aren't
 * group-by-able directly — they are aggregations evaluated *at* the level
 * defined by the group-by items.
 *
 * `MetricSpec` carries:
 * - [whereFilterSpecs] — filters applied during evaluation of this metric.
 * - [offsetWindow] / [offsetToGrain] — for time-offset metrics (cumulative,
 *   period-over-period).
 *
 * The Python factory `create_from_input_metric` builds a `MetricSpec` from a
 * `PydanticMetricInput` via a `WhereFilterSpecFactory`. That factory is
 * deferred to W7c/W8 because it depends on semantic-graph types; the W7b port
 * only includes the simple [create] / [fromElementName] / [fromReference]
 * constructors and runtime mutation helpers.
 */
data class MetricSpec(
    override val elementName: String,
    val whereFilterSpecs: List<WhereFilterSpec>,
    val alias: String?,
    val offsetWindow: TimeWindow?,
    val offsetToGrain: TimeGranularity?,
) : InstanceSpec, Comparable<MetricSpec> {

    override val dunderName: String get() = elementName

    /** The metric reference for the underlying element name. */
    val reference: MetricReference
        get() = MetricReference(elementName)

    /** True iff the metric has any offset configuration. */
    val hasTimeOffset: Boolean
        get() = offsetWindow != null || offsetToGrain != null

    /** The offset window if it exists and uses a standard granularity. */
    val standardOffsetWindow: TimeWindow?
        get() = offsetWindow?.takeIf { it.isStandardGranularity }

    /** The offset window if it exists and uses a custom granularity. */
    val customOffsetWindow: TimeWindow?
        get() = offsetWindow?.takeIf { !it.isStandardGranularity }

    /** Strip any time offsets. */
    fun withoutOffset(): MetricSpec =
        MetricSpec(
            elementName = elementName,
            whereFilterSpecs = whereFilterSpecs,
            alias = alias,
            offsetWindow = null,
            offsetToGrain = null,
        )

    override fun withAlias(alias: String?): MetricSpec = copy(alias = alias)

    override fun withoutFilterSpecs(): MetricSpec = copy(whereFilterSpecs = emptyList())

    /** Replace the [whereFilterSpecs] list. */
    fun withWhereFilterSpecs(whereFilterSpecs: List<WhereFilterSpec>): MetricSpec =
        copy(whereFilterSpecs = whereFilterSpecs)

    /** A "modifier" view used for grouping equivalent metric specs. */
    val metricModifier: MetricModifier
        get() = MetricModifier(
            whereFilterSpecs = whereFilterSpecs,
            alias = alias,
            offsetWindow = offsetWindow,
            offsetToGrain = offsetToGrain,
        )

    override fun <OutputT> accept(visitor: InstanceSpecVisitor<OutputT>): OutputT =
        visitor.visitMetricSpec(this)

    override fun compareTo(other: MetricSpec): Int = COMPARATOR.compare(this, other)

    companion object {
        /**
         * Constructor mirroring Python's `MetricSpec.create`.
         *
         * Required so callers can omit modifier fields without sprinkling
         * `null` literals throughout the codebase. (Equivalent to Python's
         * keyword defaults; CLAUDE.md allows defaults at API surface entry
         * points such as construction helpers.)
         */
        fun create(
            elementName: String,
            whereFilterSpecs: Iterable<WhereFilterSpec>,
            alias: String?,
            offsetWindow: TimeWindow?,
            offsetToGrain: TimeGranularity?,
        ): MetricSpec = MetricSpec(
            elementName = elementName,
            whereFilterSpecs = whereFilterSpecs.toList(),
            alias = alias,
            offsetWindow = offsetWindow,
            offsetToGrain = offsetToGrain,
        )

        /** Build a bare [MetricSpec] from just an element name. */
        fun fromElementName(elementName: String): MetricSpec = create(
            elementName = elementName,
            whereFilterSpecs = emptyList(),
            alias = null,
            offsetWindow = null,
            offsetToGrain = null,
        )

        /** Construct from a [MetricReference]. */
        fun fromReference(reference: MetricReference): MetricSpec = fromElementName(reference.elementName)

        // Python's `@dataclass(order=True)` derives a tuple-of-fields ordering.
        private val COMPARATOR: Comparator<MetricSpec> = compareBy(
            { it.elementName },
            { it.alias ?: "" },
        )
    }
}

/**
 * Describes how a metric should be modified.
 *
 * Port of `metricflow_semantics.specs.metric_spec.MetricModifier`.
 *
 * Used to group metrics that can be consolidated into a single query, e.g.
 * those that share the same filters / offset.
 */
data class MetricModifier(
    val whereFilterSpecs: List<WhereFilterSpec>,
    val alias: String?,
    val offsetWindow: TimeWindow?,
    val offsetToGrain: TimeGranularity?,
)
