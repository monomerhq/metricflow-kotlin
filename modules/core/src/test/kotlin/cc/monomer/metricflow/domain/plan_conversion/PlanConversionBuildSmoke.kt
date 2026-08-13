package cc.monomer.metricflow.domain.plan_conversion

import cc.monomer.metricflow.domain.plan_conversion.helpers.SelectColumnSet
import cc.monomer.metricflow.domain.plan_conversion.helpers.SqlExpressionBuilder
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAggregateFunctionExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanConversionBuildSmoke {

    @Test
    fun `select column set empty merges to itself`() {
        val merged = SelectColumnSet.EMPTY.merge(SelectColumnSet.EMPTY)
        assertEquals(SelectColumnSet.EMPTY, merged)
        assertTrue(merged.columnsInDefaultOrder.isEmpty())
    }

    @Test
    fun `single alias coalesce is plain column reference`() {
        val expr = SqlExpressionBuilder.makeCoalescedExpr(listOf("a"), "is_instant")
        assertTrue(expr is SqlColumnReferenceExpression)
        assertEquals("a", expr.colRef.tableAlias)
        assertEquals("is_instant", expr.colRef.columnName)
    }

    @Test
    fun `multi alias coalesce wraps in COALESCE`() {
        val expr = SqlExpressionBuilder.makeCoalescedExpr(listOf("a", "b"), "is_instant")
        assertTrue(expr is SqlAggregateFunctionExpression)
        assertEquals(SqlFunction.COALESCE, expr.sqlFunction)
        assertEquals(2, expr.sqlFunctionArgs.size)
    }
}
