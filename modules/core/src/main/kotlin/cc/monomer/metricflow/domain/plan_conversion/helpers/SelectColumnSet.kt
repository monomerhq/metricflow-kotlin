package cc.monomer.metricflow.domain.plan_conversion.helpers

import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn

/**
 * A set of SQL SELECT columns binned by the instance-type they came from.
 *
 * Port of `metricflow.plan_conversion.select_column_gen.SelectColumnSet`.
 *
 * The conversion layer produces columns in seven buckets; downstream code typically merges
 * many sets (e.g. one set per joined source) and then asks for [columnsInDefaultOrder] to lay
 * out the final SELECT clause. This is the same shape as Python's dataclass — preserved verbatim
 * for diff-runner parity.
 */
data class SelectColumnSet(
    val metricColumns: List<SqlSelectColumn>,
    val simpleMetricInputColumns: List<SqlSelectColumn>,
    val dimensionColumns: List<SqlSelectColumn>,
    val timeDimensionColumns: List<SqlSelectColumn>,
    val entityColumns: List<SqlSelectColumn>,
    val groupByMetricColumns: List<SqlSelectColumn>,
    val metadataColumns: List<SqlSelectColumn>,
) : Mergeable<SelectColumnSet> {

    /**
     * Return the columns in the canonical output order used in rendered SELECT clauses.
     * Port of `columns_in_default_order`. The order matches the sequence data consumers
     * typically prefer (time dim → entity → dim → group-by metric → metric → simple metric
     * input → metadata).
     */
    val columnsInDefaultOrder: List<SqlSelectColumn>
        get() = timeDimensionColumns +
            entityColumns +
            dimensionColumns +
            groupByMetricColumns +
            metricColumns +
            simpleMetricInputColumns +
            metadataColumns

    override fun merge(other: SelectColumnSet): SelectColumnSet = SelectColumnSet(
        metricColumns = metricColumns + other.metricColumns,
        simpleMetricInputColumns = simpleMetricInputColumns + other.simpleMetricInputColumns,
        dimensionColumns = dimensionColumns + other.dimensionColumns,
        timeDimensionColumns = timeDimensionColumns + other.timeDimensionColumns,
        entityColumns = entityColumns + other.entityColumns,
        groupByMetricColumns = groupByMetricColumns + other.groupByMetricColumns,
        metadataColumns = metadataColumns + other.metadataColumns,
    )

    companion object {
        /** The empty [SelectColumnSet] — identity for [merge]. */
        val EMPTY: SelectColumnSet = SelectColumnSet(
            metricColumns = emptyList(),
            simpleMetricInputColumns = emptyList(),
            dimensionColumns = emptyList(),
            timeDimensionColumns = emptyList(),
            entityColumns = emptyList(),
            groupByMetricColumns = emptyList(),
            metadataColumns = emptyList(),
        )

        /**
         * Factory matching Python's `SelectColumnSet.create(...)` — every bucket has a
         * defaulted empty value in Python; in Kotlin we surface explicit overloads so the
         * project's "no default parameter values" rule is preserved.
         */
        fun create(
            metricColumns: List<SqlSelectColumn>,
            simpleMetricInputColumns: List<SqlSelectColumn>,
            dimensionColumns: List<SqlSelectColumn>,
            timeDimensionColumns: List<SqlSelectColumn>,
            entityColumns: List<SqlSelectColumn>,
            groupByMetricColumns: List<SqlSelectColumn>,
            metadataColumns: List<SqlSelectColumn>,
        ): SelectColumnSet = SelectColumnSet(
            metricColumns = metricColumns,
            simpleMetricInputColumns = simpleMetricInputColumns,
            dimensionColumns = dimensionColumns,
            timeDimensionColumns = timeDimensionColumns,
            entityColumns = entityColumns,
            groupByMetricColumns = groupByMetricColumns,
            metadataColumns = metadataColumns,
        )

        /** Convenience: build a [SelectColumnSet] containing only dimension/time-dim/entity buckets. */
        fun ofLinkable(
            dimensionColumns: List<SqlSelectColumn>,
            timeDimensionColumns: List<SqlSelectColumn>,
            entityColumns: List<SqlSelectColumn>,
        ): SelectColumnSet = SelectColumnSet(
            metricColumns = emptyList(),
            simpleMetricInputColumns = emptyList(),
            dimensionColumns = dimensionColumns,
            timeDimensionColumns = timeDimensionColumns,
            entityColumns = entityColumns,
            groupByMetricColumns = emptyList(),
            metadataColumns = emptyList(),
        )

        /** Convenience: build a [SelectColumnSet] containing only simple-metric-input columns. */
        fun ofSimpleMetricInputs(simpleMetricInputColumns: List<SqlSelectColumn>): SelectColumnSet =
            SelectColumnSet(
                metricColumns = emptyList(),
                simpleMetricInputColumns = simpleMetricInputColumns,
                dimensionColumns = emptyList(),
                timeDimensionColumns = emptyList(),
                entityColumns = emptyList(),
                groupByMetricColumns = emptyList(),
                metadataColumns = emptyList(),
            )
    }
}
