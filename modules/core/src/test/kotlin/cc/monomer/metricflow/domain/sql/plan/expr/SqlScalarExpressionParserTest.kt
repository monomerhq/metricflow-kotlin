package cc.monomer.metricflow.domain.sql.plan.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlScalarExpressionParserTest {

    @Test
    fun `returns identifiers from a scalar expression`() {
        assertEquals(
            setOf("orders", "refunds"),
            SqlScalarExpressionParser.referencedColumnNames("orders - coalesce(refunds, 0)"),
        )
    }

    @Test
    fun `rejects trailing input`() {
        assertFailsWith<IllegalArgumentException> {
            SqlScalarExpressionParser.referencedColumnNames("orders + 1 trailing")
        }
    }

    @Test
    fun `rejects query-producing expressions`() {
        assertFailsWith<IllegalArgumentException> {
            SqlScalarExpressionParser.referencedColumnNames("(SELECT orders FROM sales)")
        }
        assertFailsWith<IllegalArgumentException> {
            SqlScalarExpressionParser.referencedColumnNames("EXISTS (SELECT 1)")
        }
        assertFailsWith<IllegalArgumentException> {
            SqlScalarExpressionParser.referencedColumnNames("orders IN (SELECT order_id FROM sales)")
        }
        assertFailsWith<IllegalArgumentException> {
            SqlScalarExpressionParser.referencedColumnNames("TABLE(read_orders())")
        }
    }

    @Test
    fun `rejects a second statement`() {
        assertFailsWith<IllegalArgumentException> {
            SqlScalarExpressionParser.referencedColumnNames("orders; SELECT 1")
        }
    }
}
