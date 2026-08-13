package cc.monomer.metricflow.domain.plan_conversion.to_sql_plan

import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.InputSpecOrder
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import kotlin.test.Test
import kotlin.test.assertEquals

class OutputColumnOrdererTest {

    private fun col(alias: String): SqlSelectColumn = SqlSelectColumn(
        expr = SqlColumnReferenceExpression.fromColumnReference("t", alias),
        columnAlias = alias,
    )

    private val countryDim = DimensionSpec(elementName = "country", entityLinks = emptyList(), alias = null)
    private val userEntity = EntitySpec(elementName = "user_id", entityLinks = emptyList(), alias = null)
    private val bookingsMetric = MetricSpec.fromElementName("bookings")

    private val mapping: Map<InstanceSpec, List<SqlSelectColumn>> = linkedMapOf(
        bookingsMetric as InstanceSpec to listOf(col("bookings")),
        countryDim to listOf(col("country")),
        userEntity to listOf(col("user_id")),
    )

    @Test
    fun `type grouped orderer puts entity before dimension before metric`() {
        val orderer = TypeGroupedOrderer()
        val ordered = orderer.orderColumns(mapping)
        assertEquals(listOf("user_id", "country", "bookings"), ordered.map { it.columnAlias })
    }

    @Test
    fun `input order preserving uses query input order`() {
        val orderer = InputOrderPreservingOrderer(
            InputSpecOrder(
                groupByItemSpecs = listOf(countryDim, userEntity),
                metricSpecs = listOf(bookingsMetric),
            ),
        )
        val ordered = orderer.orderColumns(mapping)
        assertEquals(listOf("country", "user_id", "bookings"), ordered.map { it.columnAlias })
    }

    @Test
    fun `input order preserving falls back when mismatched`() {
        // Mismatch: input order references a spec not in the mapping.
        val unknownEntity = EntitySpec(elementName = "missing", entityLinks = emptyList(), alias = null)
        val orderer = InputOrderPreservingOrderer(
            InputSpecOrder(
                groupByItemSpecs = listOf(countryDim, unknownEntity),
                metricSpecs = listOf(bookingsMetric),
            ),
        )
        val ordered = orderer.orderColumns(mapping)
        // Falls back to TypeGroupedOrderer output.
        assertEquals(listOf("user_id", "country", "bookings"), ordered.map { it.columnAlias })
    }

    @Test
    fun `entity references compile with EntityReference type`() {
        // Defensive: ensure the test can reference an EntityReference symbol to keep the
        // module-graph link warm even in CI minimal-import strict mode.
        val ref = EntityReference(elementName = "user_id")
        assertEquals("user_id", ref.elementName)
    }
}
