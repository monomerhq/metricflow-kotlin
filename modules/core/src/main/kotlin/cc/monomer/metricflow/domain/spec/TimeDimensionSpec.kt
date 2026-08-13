package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import cc.monomer.metricflow.domain.spec.naming.StructuredLinkableSpecName
import cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowFunction

/** Default granularity used when no grain or date_part is constrained. */
val DEFAULT_TIME_GRANULARITY: TimeGranularity = TimeGranularity.DAY

/**
 * Fields of [TimeDimensionSpec] that can be ignored when grouping by
 * [TimeDimensionSpecComparisonKey].
 *
 * Port of `metricflow_semantics.specs.time_dimension_spec.TimeDimensionSpecField`.
 */
enum class TimeDimensionSpecField(val value: String) {
    TIME_GRANULARITY("time_granularity"),
}

/**
 * A spec for a time-dimension column.
 *
 * Port of `metricflow_semantics.specs.time_dimension_spec.TimeDimensionSpec`.
 *
 * Time dimensions carry either a [timeGranularity] (e.g. `DAY`, `MONTH`) or a
 * [datePart] (extract a part of the timestamp), but never both — enforced in
 * `init`. The [aggregationState] is set when the spec flows through semi-
 * additive joins; [windowFunctions] tracks any window aggregations applied
 * (e.g. `LEAD` for cumulative metrics).
 */
data class TimeDimensionSpec(
    override val elementName: String,
    override val entityLinks: List<EntityReference>,
    val timeGranularity: ExpandedTimeGranularity?,
    val datePart: DatePart?,
    val aggregationState: AggregationState?,
    val windowFunctions: List<SqlWindowFunction>,
    override val alias: String?,
) : LinkableInstanceSpec {

    init {
        val both = timeGranularity != null && datePart != null
        val neither = timeGranularity == null && datePart == null
        require(!both && !neither) {
            "Exactly one of `timeGranularity` and `datePart` must be set " +
                "(timeGranularity=$timeGranularity, datePart=$datePart)"
        }
    }

    /** `true` if this spec uses a custom-named granularity (e.g. `fiscal_quarter`). */
    val hasCustomGrain: Boolean
        get() = timeGranularity != null && timeGranularity.isCustomGranularity

    /** The custom-or-standard granularity name, or `null` when [datePart] is set. */
    val timeGranularityName: String?
        get() = timeGranularity?.name

    /** The underlying standard grain (custom granularities map back to standard). */
    val baseGranularity: TimeGranularity?
        get() = timeGranularity?.baseGranularity

    /**
     * Sort key for ordering time dimension specs by base granularity.
     *
     * Specs without a base granularity (date_part-only) sort last
     * (value 100, larger than the largest [TimeGranularity.toInt]).
     */
    val baseGranularitySortKey: Int
        get() = baseGranularity?.toInt() ?: 100

    /** Returns `true` when this spec describes `metric_time`. */
    val isMetricTime: Boolean
        get() = elementName == METRIC_TIME_ELEMENT_NAME

    override val reference: TimeDimensionReference
        get() = TimeDimensionReference(elementName)

    /** Dimension reference for the underlying element name. */
    val dimensionReference: DimensionReference
        get() = DimensionReference(elementName)

    override val dunderName: String
        get() = StructuredLinkableSpecName(
            entityLinkNames = entityLinks.map { it.elementName },
            elementName = elementName,
            timeGranularityName = timeGranularityName,
            datePart = datePart,
            metricSubqueryEntityLinkNames = null,
        ).dunderName

    override fun withoutFirstEntityLink(): TimeDimensionSpec {
        check(entityLinks.isNotEmpty()) { "Spec does not have any entity links: $this" }
        return copy(entityLinks = entityLinks.drop(1), alias = null)
    }

    override fun withoutEntityLinks(): TimeDimensionSpec =
        copy(entityLinks = emptyList(), alias = null)

    override fun withEntityPrefix(entityPrefix: EntityReference): TimeDimensionSpec =
        copy(entityLinks = listOf(entityPrefix) + entityLinks)

    /** Replace the granularity, dropping any date_part. */
    fun withGrain(timeGranularity: ExpandedTimeGranularity): TimeDimensionSpec =
        TimeDimensionSpec(
            elementName = elementName,
            entityLinks = entityLinks,
            timeGranularity = timeGranularity,
            datePart = null,
            aggregationState = aggregationState,
            windowFunctions = windowFunctions,
            alias = alias,
        )

    /** Replace the granularity with its base grain (lifts custom grains). */
    fun withBaseGrain(): TimeDimensionSpec {
        val baseGrain = baseGranularity?.let { ExpandedTimeGranularity.fromTimeGranularity(it) }
        return TimeDimensionSpec(
            elementName = elementName,
            entityLinks = entityLinks,
            timeGranularity = baseGrain,
            // When granularity becomes null (no base granularity), we must keep the
            // existing datePart (otherwise the `exactly one set` invariant fails).
            datePart = if (baseGrain == null) datePart else null,
            aggregationState = aggregationState,
            windowFunctions = windowFunctions,
            alias = alias,
        )
    }

    /** Return a copy with [aggregationState] replaced. */
    fun withAggregationState(aggregationState: AggregationState): TimeDimensionSpec =
        copy(aggregationState = aggregationState)

    /** Return a copy with [windowFunctions] replaced. */
    fun withWindowFunctions(windowFunctions: List<SqlWindowFunction>): TimeDimensionSpec =
        copy(windowFunctions = windowFunctions)

    override fun withAlias(alias: String?): TimeDimensionSpec = copy(alias = alias)

    /** A key for grouping specs while ignoring the supplied fields. */
    fun comparisonKey(excludeFields: Set<TimeDimensionSpecField>): TimeDimensionSpecComparisonKey =
        TimeDimensionSpecComparisonKey(this, excludeFields)

    override fun <OutputT> accept(visitor: InstanceSpecVisitor<OutputT>): OutputT =
        visitor.visitTimeDimensionSpec(this)

    companion object {
        /**
         * Generate every possible spec for a time dimension reference / entity
         * link pair, covering all granularities (standard + custom) plus every
         * [DatePart].
         */
        fun generatePossibleSpecsForTimeDimension(
            timeDimensionReference: TimeDimensionReference,
            entityLinks: List<EntityReference>,
            customGranularities: Map<String, ExpandedTimeGranularity>,
        ): List<TimeDimensionSpec> {
            val granularities: List<ExpandedTimeGranularity> =
                TimeGranularity.entries.map { ExpandedTimeGranularity.fromTimeGranularity(it) } +
                    customGranularities.values
            val specs = mutableListOf<TimeDimensionSpec>()
            for (g in granularities) {
                specs.add(
                    TimeDimensionSpec(
                        elementName = timeDimensionReference.elementName,
                        entityLinks = entityLinks,
                        timeGranularity = g,
                        datePart = null,
                        aggregationState = null,
                        windowFunctions = emptyList(),
                        alias = null,
                    ),
                )
            }
            for (d in DatePart.entries) {
                specs.add(
                    TimeDimensionSpec(
                        elementName = timeDimensionReference.elementName,
                        entityLinks = entityLinks,
                        timeGranularity = null,
                        datePart = d,
                        aggregationState = null,
                        windowFunctions = emptyList(),
                        alias = null,
                    ),
                )
            }
            return specs
        }

        /**
         * Return the list of time dimension specs, replacing any custom grains with base grains.
         *
         * Dedupes new specs but preserves initial order.
         */
        fun withBaseGrains(specs: Iterable<TimeDimensionSpec>): List<TimeDimensionSpec> {
            val seen = LinkedHashSet<TimeDimensionSpec>()
            for (spec in specs) {
                seen.add(spec.withBaseGrain())
            }
            return seen.toList()
        }
    }
}

/**
 * A key that can be used for comparing or grouping [TimeDimensionSpec]s while
 * ignoring specific attributes.
 *
 * Port of
 * `metricflow_semantics.specs.time_dimension_spec.TimeDimensionSpecComparisonKey`.
 *
 * Two keys can only meaningfully be compared if they exclude the same set of
 * fields. Useful for ambiguous group-by-item resolution where we want to
 * select a time dimension regardless of the grain.
 */
class TimeDimensionSpecComparisonKey(
    val sourceSpec: TimeDimensionSpec,
    excludeFields: Set<TimeDimensionSpecField>,
) {
    private val excludedFields: Set<TimeDimensionSpecField> = excludeFields.toSet()

    private val comparisonValues: List<Any?> = buildList {
        add(sourceSpec.elementName)
        add(sourceSpec.entityLinks)
        if (TimeDimensionSpecField.TIME_GRANULARITY !in excludedFields) {
            add(sourceSpec.timeGranularity)
        }
        add(sourceSpec.datePart)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is TimeDimensionSpecComparisonKey) return false
        if (excludedFields != other.excludedFields) return false
        return comparisonValues == other.comparisonValues
    }

    override fun hashCode(): Int = 31 * excludedFields.hashCode() + comparisonValues.hashCode()
}
