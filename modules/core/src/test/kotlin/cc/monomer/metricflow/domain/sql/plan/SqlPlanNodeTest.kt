package cc.monomer.metricflow.domain.sql.plan

import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIntegerExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqlPlanNodeTest {

    @Test
    fun `table node carries the SqlTable and exposes itself via as-helper`() {
        val table = SqlTable(schemaName = "ana", tableName = "events")
        val node = SqlTableNode.create(table)
        assertEquals(table, node.sqlTable)
        assertEquals(node, node.asSqlTableNode)
        assertNull(node.asSelectNode)
    }

    @Test
    fun `select statement exposes columns via nearestSelectColumns`() {
        val table = SqlTableNode.create(SqlTable(schemaName = "ana", tableName = "events"))
        val select = SqlSelectStatementNode.create(
            description = "test",
            selectColumns = listOf(SqlSelectColumn.fromColumnReference("events", "id")),
            fromSource = table,
            fromSourceAlias = "events",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val columns = select.nearestSelectColumns(SqlCteAliasMapping.EMPTY)
        assertNotNull(columns)
        assertEquals(1, columns.size)
        assertEquals("id", columns[0].columnAlias)
    }

    @Test
    fun `select-text node returns null for nearestSelectColumns`() {
        val node = SqlSelectTextNode.create("SELECT 1")
        assertNull(node.nearestSelectColumns(SqlCteAliasMapping.EMPTY))
    }

    @Test
    fun `cte alias mapping resolves a SqlTableNode aliased at a CTE`() {
        val inner = SqlSelectStatementNode.create(
            description = "inner",
            selectColumns = listOf(SqlSelectColumn.fromColumnReference("src", "x")),
            fromSource = SqlTableNode.create(SqlTable(schemaName = "ana", tableName = "src")),
            fromSourceAlias = "src",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val cte = SqlCteNode.create(inner, "my_cte")
        val mapping = SqlCteAliasMapping.create(mapOf("my_cte" to cte))
        // A SqlTableNode pointing at the CTE name (no schema) must resolve to the CTE's columns.
        val tableAtCte = SqlTableNode.create(SqlTable(schemaName = null, tableName = "my_cte"))
        val cols = tableAtCte.nearestSelectColumns(mapping)
        assertNotNull(cols)
        assertEquals("x", cols[0].columnAlias)
    }

    @Test
    fun `ctas node has exactly one parent and exposes it`() {
        val select = SqlSelectTextNode.create("SELECT 1")
        val ctas = SqlCreateTableAsNode.create(
            sqlTable = SqlTable(schemaName = "ana", tableName = "snap"),
            parentNode = select,
        )
        assertEquals(select, ctas.parentNode)
        assertEquals(1, ctas.parentNodes.size)
    }

    @Test
    fun `withSelectColumns returns a copy with replaced columns`() {
        val table = SqlTableNode.create(SqlTable(schemaName = "ana", tableName = "events"))
        val select = SqlSelectStatementNode.create(
            description = "x",
            selectColumns = listOf(SqlSelectColumn.fromColumnReference("events", "a")),
            fromSource = table,
            fromSourceAlias = "events",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val newCol = SqlSelectColumn(expr = SqlIntegerExpression.create(7), columnAlias = "lit")
        val replaced = select.withSelectColumns(listOf(newCol))
        assertEquals(1, replaced.selectColumns.size)
        assertEquals("lit", replaced.selectColumns[0].columnAlias)
        assertEquals(select.fromSourceAlias, replaced.fromSourceAlias)
    }

    @Test
    fun `SqlPlan carries the render node as the sole sink`() {
        val node = SqlTableNode.create(SqlTable(schemaName = "ana", tableName = "events"))
        val plan = SqlPlan(node)
        assertEquals(listOf(node), plan.sinkNodes)
        assertEquals(node, plan.renderNode)
    }
}
