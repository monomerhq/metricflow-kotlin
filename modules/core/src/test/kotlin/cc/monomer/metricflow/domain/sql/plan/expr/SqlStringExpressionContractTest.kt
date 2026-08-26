package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlStringExpressionContractTest {

    @Test
    fun `opaque expression preserves the original text`() {
        val sql = "(SELECT orders FROM sales); SELECT 1"
        val expression = SqlStringExpression.create(
            sqlExpr = sql,
            bindParameterSet = SqlBindParameterSet.EMPTY,
            requiresParenthesis = false,
            usedColumns = null,
        )

        assertEquals(
            sql,
            expression.sqlExpr,
        )
    }

    @Test
    fun `used columns remain optional optimizer metadata`() {
        val expression = SqlStringExpression.create(
            sqlExpr = "orders - coalesce(refunds, 0)",
            bindParameterSet = SqlBindParameterSet.EMPTY,
            requiresParenthesis = false,
            usedColumns = listOf("orders", "refunds"),
        )

        assertEquals(listOf("orders", "refunds"), expression.usedColumns)
    }
}
