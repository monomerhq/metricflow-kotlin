package cc.monomer.metricflow.domain.query.input

import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.naming.QueryItemNamingScheme
import cc.monomer.metricflow.domain.query.parameter.GroupByQueryParameter
import cc.monomer.metricflow.domain.query.parameter.MetricQueryParameter
import cc.monomer.metricflow.domain.query.parameter.OrderByQueryParameter
import cc.monomer.metricflow.domain.spec.pattern.SpecPattern

/**
 * Concrete resolver-input variants.
 *
 * Port of
 * `metricflow_semantics.query.resolver_inputs.query_resolver_inputs.*`.
 *
 * Each variant implements [MetricFlowQueryResolverInput]; together they
 * form a closed family — Kotlin enforces the closure via the sealed root.
 */

/**
 * An input string that doesn't follow any known naming scheme.
 *
 * Port of `InvalidStringInput`. Produced by the query parser before the
 * resolver is invoked; carried into the resolver only so the eventual
 * error message can reference the offending input.
 */
data class InvalidStringInput(
    val inputObj: String,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = inputObj
}

/**
 * A metric reference (either a bare string or a [MetricQueryParameter]).
 *
 * Port of `ResolverInputForMetric`.
 */
data class ResolverInputForMetric(
    val inputObj: Any,
    val namingScheme: QueryItemNamingScheme,
    val specPattern: SpecPattern,
    val alias: String?,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = inputObj.toString()

    override val inputPatternDescription: InputPatternDescription
        get() = InputPatternDescription(namingScheme = namingScheme, specPattern = specPattern)
}

/**
 * A group-by-item reference (either a bare string or a [GroupByQueryParameter]).
 *
 * Port of `ResolverInputForGroupByItem`.
 */
data class ResolverInputForGroupByItem(
    val inputObj: Any,
    val inputObjNamingScheme: QueryItemNamingScheme,
    val specPattern: SpecPattern,
    val alias: String?,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = inputObj.toString()

    override val inputPatternDescription: InputPatternDescription
        get() = InputPatternDescription(namingScheme = inputObjNamingScheme, specPattern = specPattern)
}

/**
 * An order-by reference that resolves to either a metric or a group-by item.
 *
 * Port of `ResolverInputForOrderByItem`. Carries [possibleInputs] because at
 * parse time a string order-by could match either a metric or a group-by
 * item; the resolver picks the right one against the resolved spec set.
 */
data class ResolverInputForOrderByItem(
    val inputObj: Any,
    val possibleInputs: List<MetricFlowQueryResolverInput>,
    val descending: Boolean,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = inputObj.toString()

    init {
        for (input in possibleInputs) {
            require(input is ResolverInputForMetric || input is ResolverInputForGroupByItem) {
                "possibleInputs must be ResolverInputForMetric or ResolverInputForGroupByItem; got $input"
            }
        }
    }
}

/** The query's `limit` value. Port of `ResolverInputForLimit`. */
data class ResolverInputForLimit(
    val limit: Int?,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = limit.toString()
}

/** The `min_max_only` flag. Port of `ResolverInputForMinMaxOnly`. */
data class ResolverInputForMinMaxOnly(
    val minMaxOnly: Boolean,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = minMaxOnly.toString()
}

/** The `apply_group_by` flag. Port of `ResolverInputForApplyGroupBy`. */
data class ResolverInputForApplyGroupBy(
    val applyGroupBy: Boolean,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = applyGroupBy.toString()
}

/**
 * The query-level where-filter intersection.
 *
 * Port of `ResolverInputForQueryLevelWhereFilterIntersection`.
 */
data class ResolverInputForQueryLevelWhereFilterIntersection(
    val whereFilterIntersection: WhereFilterIntersection,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String
        get() = buildString {
            append("WhereFilter(\n")
            for (filter in whereFilterIntersection.whereFilters) {
                append("  ").append(filter.whereSqlTemplate).append('\n')
            }
            append(")")
        }
}

/**
 * A where-filter anywhere inside the resolution DAG (query-level or
 * metric-level).
 *
 * Port of `ResolverInputForWhereFilterIntersection`.
 */
data class ResolverInputForWhereFilterIntersection(
    val whereFilterIntersection: WhereFilterIntersection,
    val filterResolutionPath: MetricFlowQueryResolutionPath,
    val objectBuilderStr: String?,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String
        get() = buildString {
            append("WhereFilter(\n")
            for (filter in whereFilterIntersection.whereFilters) {
                append("  ").append(filter.whereSqlTemplate).append('\n')
            }
            append(")\n")
            append("Filter Path:\n")
            append("  ").append(filterResolutionPath.uiDescription)
            if (objectBuilderStr != null) {
                append("\nObject Builder Input:\n")
                append("  ").append(objectBuilderStr)
            }
        }
}

/**
 * The full query — every input the resolver needs in one record.
 *
 * Port of `ResolverInputForQuery`.
 */
data class ResolverInputForQuery(
    val metricInputs: List<ResolverInputForMetric>,
    val groupByItemInputs: List<ResolverInputForGroupByItem>,
    val filterInput: ResolverInputForQueryLevelWhereFilterIntersection,
    val orderByItemInputs: List<ResolverInputForOrderByItem>,
    val limitInput: ResolverInputForLimit,
    val minMaxOnly: ResolverInputForMinMaxOnly,
    val applyGroupBy: ResolverInputForApplyGroupBy,
) : MetricFlowQueryResolverInput {
    // The error formatter consults the resolution path directly; this string
    // is intentionally empty (Python parity).
    override val uiDescription: String get() = ""
}

/**
 * A reference to a metric query parameter input. Wraps the [MetricQueryParameter]
 * so the resolver can resolve it via the parameter's own resolver-input factory.
 *
 * Not in Python — this Kotlin-only type is used by [ResolverInputForOrderByItem]
 * when the order-by carries an object parameter rather than a string.
 */
data class ResolverInputForMetricParameter(
    val parameter: MetricQueryParameter,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = parameter.toString()
}

/** Wrap a generic group-by-parameter input. */
data class ResolverInputForGroupByParameter(
    val parameter: GroupByQueryParameter,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = parameter.toString()
}

/** Wrap a generic order-by-parameter input. */
data class ResolverInputForOrderByParameter(
    val parameter: OrderByQueryParameter,
) : MetricFlowQueryResolverInput {
    override val uiDescription: String get() = parameter.toString()
}
