package cc.monomer.metricflow.domain.sql.plan.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlStringExpressionContractTest {

    @Test
    fun `public API returns columns for a scalar expression`() {
        assertEquals(
            setOf("orders", "refunds"),
            SqlStringExpression.referencedColumnNames("orders - coalesce(refunds, 0)"),
        )
    }

    @Test
    fun `public API rejects relation-producing expressions`() {
        assertFailsWith<IllegalArgumentException> {
            SqlStringExpression.referencedColumnNames("(SELECT orders FROM sales)")
        }
    }
}
