package cc.monomer.metricflow.domain.sql.plan

import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColumnAliasRenamerTest {

    private fun buildSelect(alias: String): SqlSelectStatementNode = SqlSelectStatementNode.create(
        description = "rename test",
        selectColumns = listOf(SqlSelectColumn.fromColumnReference("src", "x").copyWithNewAlias(alias)),
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

    @Test
    fun `rename replaces select-column aliases in place`() {
        val renamer = ColumnAliasRenamer()
        val before = buildSelect("col_0")
        val after = renamer.rename(before, mapOf("col_0" to "col_1"))
        assertEquals(1, after.selectColumns.size)
        assertEquals("col_1", after.selectColumns[0].columnAlias)
        // The original is untouched.
        assertEquals("col_0", before.selectColumns[0].columnAlias)
    }

    @Test
    fun `renameViaSubquery wraps in an outer select using the new names`() {
        val renamer = ColumnAliasRenamer()
        val before = buildSelect("col_0")
        val after = renamer.renameViaSubquery(before, mapOf("col_0" to "col_1"), description = "renamed", innerQueryAlias = "subq")
        assertEquals("subq", after.fromSourceAlias)
        assertEquals(1, after.selectColumns.size)
        assertEquals("col_1", after.selectColumns[0].columnAlias)
        // Inner SELECT exposed via from_source.
        val innerSelect = after.fromSource as SqlSelectStatementNode
        assertTrue(innerSelect.selectColumns.any { it.columnAlias == "col_1" })
    }
}
