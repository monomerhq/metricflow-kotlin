package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlComparison
import cc.monomer.metricflow.domain.sql.plan.expr.SqlComparisonExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlJoinDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlTableAliasSimplifierTest {

    private val optimizer = SqlTableAliasSimplifier()

    @Test
    fun `drops table alias from select columns when there are no joins`() {
        val select = TestPlanFixtures.simpleSelect(
            selectColumns = listOf(TestPlanFixtures.selectCol("events", "id")),
            fromTable = TestPlanFixtures.tableNode("ana", "events"),
            fromAlias = "events",
        )
        val optimized = optimizer.optimize(select) as SqlSelectStatementNode
        val expr = optimized.selectColumns[0].expr as SqlColumnReferenceExpression
        assertFalse(expr.shouldRenderTableAlias)
        assertEquals("id", expr.colRef.columnName)
    }

    @Test
    fun `preserves table alias when there are joins`() {
        val leftTable = TestPlanFixtures.tableNode("ana", "events")
        val rightTable = TestPlanFixtures.tableNode("ana", "users")
        val join = SqlJoinDescription(
            rightSource = rightTable,
            rightSourceAlias = "users",
            joinType = SqlJoinType.INNER,
            onCondition = SqlComparisonExpression.create(
                leftExpr = TestPlanFixtures.col("events", "user_id"),
                comparison = SqlComparison.EQUALS,
                rightExpr = TestPlanFixtures.col("users", "id"),
            ),
        )
        val select = TestPlanFixtures.simpleSelect(
            selectColumns = listOf(TestPlanFixtures.selectCol("events", "id")),
            fromTable = leftTable,
            fromAlias = "events",
            joinDescs = listOf(join),
        )

        val optimized = optimizer.optimize(select) as SqlSelectStatementNode
        val expr = optimized.selectColumns[0].expr as SqlColumnReferenceExpression
        assertTrue(expr.shouldRenderTableAlias, "alias retained because of join")
    }

    @Test
    fun `recurses into sub-queries`() {
        val innerSelect = TestPlanFixtures.simpleSelect(
            description = "inner",
            selectColumns = listOf(TestPlanFixtures.selectCol("events", "id")),
            fromTable = TestPlanFixtures.tableNode("ana", "events"),
            fromAlias = "events",
        )
        val outer = TestPlanFixtures.selectFromSubquery(
            outerSelectColumns = listOf(TestPlanFixtures.selectCol("inner_alias", "id")),
            innerSelect = innerSelect,
            innerAlias = "inner_alias",
        )

        val optimized = optimizer.optimize(outer) as SqlSelectStatementNode
        // Both outer and inner select have no joins → both should drop aliases.
        val outerExpr = optimized.selectColumns[0].expr as SqlColumnReferenceExpression
        assertFalse(outerExpr.shouldRenderTableAlias)

        val innerNode = optimized.fromSource as SqlSelectStatementNode
        val innerExpr = innerNode.selectColumns[0].expr as SqlColumnReferenceExpression
        assertFalse(innerExpr.shouldRenderTableAlias)
        assertNotNull(innerNode)
    }
}
