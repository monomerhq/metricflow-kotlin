package cc.monomer.metricflow.domain.plan_conversion.node_processor

import cc.monomer.metricflow.common.errors.FeatureNotSupportedError
import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * The kinds of predicate inputs the planner can recognise for predicate pushdown.
 *
 * Port of `metricflow.plan_conversion.node_processor.PredicateInputType`.
 *
 * Used by [PredicatePushdownState] to bound which predicate categories are *currently
 * supported* for pushdown — historically a subset, because supporting pushdown for time
 * dimensions / entities without leaking unintended filters is non-trivial.
 */
enum class PredicateInputType {
    CATEGORICAL_DIMENSION,
    ENTITY,
    TIME_DIMENSION,
    TIME_RANGE_CONSTRAINT,
}

/**
 * Container for predicate-pushdown state collected and propagated during dataflow plan
 * construction.
 *
 * Port of `metricflow.plan_conversion.node_processor.PredicatePushdownState`. Tracks:
 *
 * 1. Filter predicates discovered while building the plan ([whereFilterSpecs]).
 * 2. Predicate input types that are *eligible* for pushdown given the query
 *    configuration ([pushdownEnabledTypes]).
 * 3. Filters that have already been pushed down ([appliedWhereFilterSpecs]).
 * 4. An optional [timeRangeConstraint] — the time window used to assemble a time-range
 *    filter expression.
 *
 * The validation in [validateConsistency] (Python `__post_init__`) ensures we never observe a
 * configuration that would leak pushdown operations out of a disabled branch. The factory
 * helpers below are the only legal way to derive new states.
 */
data class PredicatePushdownState(
    val timeRangeConstraint: TimeRangeConstraint?,
    /** TODO(Python): Deduplicate `whereFilterSpecs`. */
    val whereFilterSpecs: List<WhereFilterSpec>,
    val appliedWhereFilterSpecs: List<WhereFilterSpec>,
    val pushdownEnabledTypes: Set<PredicateInputType>,
) {

    init { validateConsistency() }

    /** True when pushdown is enabled for any input type with predicate candidates in place. */
    val hasPushdownPotential: Boolean
        get() = hasTimeRangeConstraintToPushDown || hasWhereFiltersToPushDown

    /**
     * True when there is a time range constraint that can be pushed down. Used as a backwards
     * compatibility shim for conversion metrics; see the Python comment for context.
     */
    val hasTimeRangeConstraintToPushDown: Boolean
        get() = PredicateInputType.TIME_RANGE_CONSTRAINT in pushdownEnabledTypes &&
            timeRangeConstraint != null

    /** True iff there are where filters available to push down. */
    val hasWhereFiltersToPushDown: Boolean
        get() = whereFilterPushdownEnabled && whereFilterSpecs.isNotEmpty()

    /** True iff pushdown is enabled for where filters (any of categorical/entity/time-dim types). */
    val whereFilterPushdownEnabled: Boolean
        get() = PredicateInputType.CATEGORICAL_DIMENSION in pushdownEnabledTypes ||
            PredicateInputType.ENTITY in pushdownEnabledTypes ||
            PredicateInputType.TIME_DIMENSION in pushdownEnabledTypes

    /**
     * The [LinkableElementType]s eligible for pushdown, derived from [pushdownEnabledTypes].
     *
     * Throws [FeatureNotSupportedError] when the state is configured for an unsupported input
     * type (today: `TIME_DIMENSION` / `ENTITY`).
     */
    val pushdownEligibleElementTypes: Set<LinkableElementType>
        get() {
            val eligible = mutableSetOf<LinkableElementType>()
            for (enabledType in pushdownEnabledTypes) {
                when (enabledType) {
                    PredicateInputType.TIME_RANGE_CONSTRAINT -> Unit
                    PredicateInputType.CATEGORICAL_DIMENSION -> eligible.add(LinkableElementType.DIMENSION)
                    PredicateInputType.TIME_DIMENSION,
                    PredicateInputType.ENTITY -> throw FeatureNotSupportedError(
                        "Predicate pushdown is not currently supported for where filter predicates " +
                            "with time dimension or entity references, but this pushdown state is " +
                            "enabled for $enabledType.",
                    )
                }
            }
            return eligible
        }

    private fun validateConsistency() {
        val invalid = pushdownEnabledTypes.filter { it == PredicateInputType.ENTITY || it == PredicateInputType.TIME_DIMENSION }
        check(invalid.isEmpty()) {
            "Unsupported predicate input type found in pushdown state configuration! We currently " +
                "only support predicate pushdown for a subset of possible predicate input types " +
                "(i.e., types of semantic manifest elements, such as entities and time dimensions, " +
                "referenced in filter predicates), but this was enabled for $pushdownEnabledTypes, " +
                "which includes the following invalid types: $invalid."
        }

        val timeRangeConstraintValid = timeRangeConstraint == null ||
            PredicateInputType.TIME_RANGE_CONSTRAINT in pushdownEnabledTypes
        val whereFilterSpecsValid = whereFilterSpecs.isEmpty() || whereFilterPushdownEnabled
        check(timeRangeConstraintValid && whereFilterSpecsValid) {
            "Invalid pushdown state configuration! Disabled pushdown state objects cannot have " +
                "properties set that may lead to improper access and use in other contexts, as that " +
                "can lead to unintended filtering operations in cases where these properties are " +
                "accessed without appropriate checks against pushdown configuration. The following " +
                "properties should be null or empty:\n" +
                "timeRangeConstraint: $timeRangeConstraint\n" +
                "whereFilterSpecs: $whereFilterSpecs"
        }
    }

    companion object {
        /** Default pushdown-enabled types: time-range constraint + categorical dimensions. */
        val DEFAULT_PUSHDOWN_ENABLED_TYPES: Set<PredicateInputType> = linkedSetOf(
            PredicateInputType.TIME_RANGE_CONSTRAINT,
            PredicateInputType.CATEGORICAL_DIMENSION,
        )

        /**
         * Factory matching Python's `PredicatePushdownState.create`. Required because Python
         * surfaces multiple defaulted fields; we surface explicit overloads to keep the
         * "no default parameter values" project rule.
         */
        fun create(
            timeRangeConstraint: TimeRangeConstraint?,
            whereFilterSpecs: Iterable<WhereFilterSpec>,
            appliedWhereFilterSpecs: Iterable<WhereFilterSpec>,
            pushdownEnabledTypes: Iterable<PredicateInputType>,
        ): PredicatePushdownState = PredicatePushdownState(
            timeRangeConstraint = timeRangeConstraint,
            whereFilterSpecs = whereFilterSpecs.toList(),
            appliedWhereFilterSpecs = LinkedHashSet(appliedWhereFilterSpecs.toList()).toList(),
            pushdownEnabledTypes = LinkedHashSet(pushdownEnabledTypes.toList()),
        )

        /** Convenience: time-range + default pushdown types. */
        fun withDefaultEnabledTypes(timeRangeConstraint: TimeRangeConstraint?): PredicatePushdownState =
            create(
                timeRangeConstraint = timeRangeConstraint,
                whereFilterSpecs = emptyList(),
                appliedWhereFilterSpecs = emptyList(),
                pushdownEnabledTypes = DEFAULT_PUSHDOWN_ENABLED_TYPES,
            )

        /**
         * Build a new state replacing [original]'s time range constraint with the supplied one.
         * Also enables [PredicateInputType.TIME_RANGE_CONSTRAINT] in [pushdownEnabledTypes].
         *
         * Port of `PredicatePushdownState.with_time_range_constraint`.
         */
        fun withTimeRangeConstraint(
            original: PredicatePushdownState,
            timeRangeConstraint: TimeRangeConstraint,
        ): PredicatePushdownState = create(
            timeRangeConstraint = timeRangeConstraint,
            whereFilterSpecs = original.whereFilterSpecs,
            appliedWhereFilterSpecs = original.appliedWhereFilterSpecs,
            pushdownEnabledTypes = original.pushdownEnabledTypes + PredicateInputType.TIME_RANGE_CONSTRAINT,
        )

        /**
         * Build a new state with [PredicateInputType.TIME_RANGE_CONSTRAINT] removed from
         * [pushdownEnabledTypes] and [timeRangeConstraint] nulled.
         *
         * Port of `PredicatePushdownState.without_time_range_constraint`.
         */
        fun withoutTimeRangeConstraint(original: PredicatePushdownState): PredicatePushdownState = create(
            timeRangeConstraint = null,
            whereFilterSpecs = original.whereFilterSpecs,
            appliedWhereFilterSpecs = original.appliedWhereFilterSpecs,
            pushdownEnabledTypes = original.pushdownEnabledTypes - PredicateInputType.TIME_RANGE_CONSTRAINT,
        )

        /** Build a new state with the [whereFilterSpecs] field replaced. */
        fun withWhereFilterSpecs(
            original: PredicatePushdownState,
            whereFilterSpecs: Iterable<WhereFilterSpec>,
        ): PredicatePushdownState = PredicatePushdownState(
            timeRangeConstraint = original.timeRangeConstraint,
            whereFilterSpecs = whereFilterSpecs.toList(),
            appliedWhereFilterSpecs = original.appliedWhereFilterSpecs,
            pushdownEnabledTypes = original.pushdownEnabledTypes,
        )

        /** Build a new state with [whereFilterSpecs] cleared. */
        fun withoutWhereFilterSpecs(original: PredicatePushdownState): PredicatePushdownState =
            withWhereFilterSpecs(original, emptyList())

        /** Build a new state with [appliedWhereFilterSpecs] replaced. */
        fun withPushdownAppliedWhereFilterSpecs(
            original: PredicatePushdownState,
            pushdownAppliedWhereFilterSpecs: Set<WhereFilterSpec>,
        ): PredicatePushdownState = create(
            timeRangeConstraint = original.timeRangeConstraint,
            pushdownEnabledTypes = original.pushdownEnabledTypes,
            whereFilterSpecs = original.whereFilterSpecs,
            appliedWhereFilterSpecs = pushdownAppliedWhereFilterSpecs,
        )

        /**
         * The disabled pushdown state — no inputs are eligible.
         *
         * Port of `PredicatePushdownState.with_pushdown_disabled`.
         */
        fun withPushdownDisabled(): PredicatePushdownState = create(
            timeRangeConstraint = null,
            whereFilterSpecs = emptyList(),
            appliedWhereFilterSpecs = emptyList(),
            pushdownEnabledTypes = emptySet(),
        )
    }
}
