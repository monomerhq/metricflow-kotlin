package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlExpressionNodeTest {

    @Test
    fun `column reference exposes itself via the as-helper`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("a", "b")
        assertNotNull(col.asColumnReferenceExpression)
        assertNull(col.asStringExpression)
    }

    @Test
    fun `null expression is matched by another null expression`() {
        assertTrue(SqlNullExpression.create().matches(SqlNullExpression.create()))
        assertFalse(SqlNullExpression.create().matches(SqlIntegerExpression.create(0)))
    }

    @Test
    fun `comparison matches when parent nodes are reference-equal and operator agrees`() {
        // Python's matches() uses identity equality on parent nodes (since SqlExpressionNode
        // is eq=False). So matches() returns true when the SAME parent objects appear on both
        // sides, with the same operator.
        val left = SqlIntegerExpression.create(1)
        val right = SqlIntegerExpression.create(2)
        val a = SqlComparisonExpression.create(left, SqlComparison.EQUALS, right)
        val b = SqlComparisonExpression.create(left, SqlComparison.EQUALS, right)
        assertTrue(a.matches(b))

        val differentOp = SqlComparisonExpression.create(left, SqlComparison.LESS_THAN, right)
        assertFalse(a.matches(differentOp))

        val differentParent = SqlComparisonExpression.create(SqlIntegerExpression.create(1), SqlComparison.EQUALS, right)
        assertFalse(a.matches(differentParent))
    }

    @Test
    fun `lineage merges across the tree`() {
        val column = SqlColumnReferenceExpression.fromColumnReference("t", "x")
        val literal = SqlIntegerExpression.create(7)
        val cmp = SqlComparisonExpression.create(column, SqlComparison.EQUALS, literal)
        val lineage = cmp.lineage
        assertEquals(1, lineage.columnReferenceExprs.size)
        assertTrue(lineage.otherExprs.contains(cmp))
    }

    @Test
    fun `rewrite passes children through and replaces column references`() {
        val src = SqlColumnReferenceExpression.fromColumnReference("a", "x")
        val replacement = SqlIntegerExpression.create(42)
        val replacements = SqlColumnReplacements(mapOf(SqlColumnReference("a", "x") to replacement))
        val cmp = SqlComparisonExpression.create(
            src,
            SqlComparison.EQUALS,
            SqlIntegerExpression.create(0),
        )
        val rewritten = cmp.rewrite(replacements, shouldRenderTableAlias = null)
        assertTrue(rewritten is SqlComparisonExpression)
        assertTrue(rewritten.leftExpr is SqlIntegerExpression)
    }

    @Test
    fun `time grain expressions carry the supplied granularity`() {
        val arg = SqlColumnReferenceExpression.fromColumnReference("t", "ts")
        val trunc = SqlDateTruncExpression.create(TimeGranularity.MONTH, arg)
        assertEquals(TimeGranularity.MONTH, trunc.timeGranularity)
        assertEquals(arg, trunc.arg)
    }

    @Test
    fun `function expression for aggregation type returns aggregate not percentile`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("a", "x")
        val expr = SqlFunctionExpression.buildExpressionFromAggregationType(
            aggregationType = cc.monomer.metricflow.domain.manifest.model.enums.AggregationType.SUM,
            sqlColumnExpression = col,
            aggParams = null,
        )
        assertTrue(expr is SqlAggregateFunctionExpression)
        assertEquals(SqlFunction.SUM, expr.sqlFunction)
    }

    @Test
    fun `visitor dispatch is per-variant`() {
        val visited = mutableListOf<String>()
        val visitor = object : RecordingVisitor(visited) {}
        SqlNullExpression.create().accept(visitor)
        SqlIntegerExpression.create(1).accept(visitor)
        SqlStringLiteralExpression.create("a").accept(visitor)
        assertEquals(listOf("null", "int", "lit"), visited)
    }
}

private abstract class RecordingVisitor(private val sink: MutableList<String>) : SqlExpressionNodeVisitor<Unit> {
    override fun visitStringExpr(node: SqlStringExpression) { sink.add("str") }
    override fun visitStringLiteralExpr(node: SqlStringLiteralExpression) { sink.add("lit") }
    override fun visitIntegerExpr(node: SqlIntegerExpression) { sink.add("int") }
    override fun visitColumnReferenceExpr(node: SqlColumnReferenceExpression) { sink.add("col") }
    override fun visitColumnAliasReferenceExpr(node: SqlColumnAliasReferenceExpression) { sink.add("colalias") }
    override fun visitComparisonExpr(node: SqlComparisonExpression) { sink.add("cmp") }
    override fun visitFunctionExpr(node: SqlAggregateFunctionExpression) { sink.add("agg") }
    override fun visitPercentileExpr(node: SqlPercentileExpression) { sink.add("perc") }
    override fun visitNullExpr(node: SqlNullExpression) { sink.add("null") }
    override fun visitLogicalExpr(node: SqlLogicalExpression) { sink.add("log") }
    override fun visitIsNullExpr(node: SqlIsNullExpression) { sink.add("isnull") }
    override fun visitCastToTimestampExpr(node: SqlCastToTimestampExpression) { sink.add("castts") }
    override fun visitDateTruncExpr(node: SqlDateTruncExpression) { sink.add("dt") }
    override fun visitExtractExpr(node: SqlExtractExpression) { sink.add("ext") }
    override fun visitSubtractTimeIntervalExpr(node: SqlSubtractTimeIntervalExpression) { sink.add("sti") }
    override fun visitAddTimeExpr(node: SqlAddTimeExpression) { sink.add("ati") }
    override fun visitRatioComputationExpr(node: SqlRatioComputationExpression) { sink.add("ratio") }
    override fun visitBetweenExpr(node: SqlBetweenExpression) { sink.add("btw") }
    override fun visitWindowFunctionExpr(node: SqlWindowFunctionExpression) { sink.add("win") }
    override fun visitGenerateUuidExpr(node: SqlGenerateUuidExpression) { sink.add("uuid") }
    override fun visitCaseExpr(node: SqlCaseExpression) { sink.add("case") }
    override fun visitArithmeticExpr(node: SqlArithmeticExpression) { sink.add("arith") }
}
