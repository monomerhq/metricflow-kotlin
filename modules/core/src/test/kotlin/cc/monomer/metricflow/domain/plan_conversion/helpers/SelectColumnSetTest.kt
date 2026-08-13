package cc.monomer.metricflow.domain.plan_conversion.helpers

import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectColumnSetTest {

    private fun col(alias: String): SqlSelectColumn = SqlSelectColumn(
        expr = SqlColumnReferenceExpression.fromColumnReference("t", alias),
        columnAlias = alias,
    )

    @Test
    fun `columnsInDefaultOrder matches metricflow ordering`() {
        val set = SelectColumnSet.create(
            metricColumns = listOf(col("m")),
            simpleMetricInputColumns = listOf(col("smi")),
            dimensionColumns = listOf(col("d")),
            timeDimensionColumns = listOf(col("td")),
            entityColumns = listOf(col("e")),
            groupByMetricColumns = listOf(col("gbm")),
            metadataColumns = listOf(col("meta")),
        )
        // Order: time → entity → dim → gbm → metric → smi → metadata
        assertEquals(
            listOf("td", "e", "d", "gbm", "m", "smi", "meta"),
            set.columnsInDefaultOrder.map { it.columnAlias },
        )
    }

    @Test
    fun `merge concatenates per-bucket lists`() {
        val a = SelectColumnSet.ofLinkable(
            dimensionColumns = listOf(col("d1")),
            timeDimensionColumns = listOf(col("td1")),
            entityColumns = emptyList(),
        )
        val b = SelectColumnSet.ofLinkable(
            dimensionColumns = listOf(col("d2")),
            timeDimensionColumns = emptyList(),
            entityColumns = listOf(col("e2")),
        )
        val merged = a.merge(b)
        assertEquals(listOf("d1", "d2"), merged.dimensionColumns.map { it.columnAlias })
        assertEquals(listOf("td1"), merged.timeDimensionColumns.map { it.columnAlias })
        assertEquals(listOf("e2"), merged.entityColumns.map { it.columnAlias })
    }
}
