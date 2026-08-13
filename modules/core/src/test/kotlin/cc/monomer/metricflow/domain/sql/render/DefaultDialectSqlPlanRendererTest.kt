package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import cc.monomer.metricflow.domain.sql.render.DefaultSqlPlanRenderer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class DefaultDialectSqlPlanRendererTest {

    private val renderer = DefaultDialectSqlPlanRenderer()

    @Test
    @Suppress("USELESS_IS_CHECK")
    fun `is a DefaultSqlPlanRenderer`() {
        // Subtype documentation: confirms the W6 dialect class inherits the W5 base.
        assertTrue(renderer is DefaultSqlPlanRenderer)
    }

    @Test
    fun `renders ANSI SQL identical to the W5 base renderer`() {
        val plan = SqlPlan(
            SqlSelectStatementNode.create(
                description = "",
                selectColumns = listOf(SqlSelectColumn.fromColumnReference("events", "id")),
                fromSource = SqlTableNode.create(SqlTable(schemaName = "ana", tableName = "events")),
                fromSourceAlias = "events",
                cteSources = emptyList(),
                joinDescs = emptyList(),
                groupBys = emptyList(),
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )

        val mine = renderer.renderSqlPlan(plan).sql
        val base = DefaultSqlPlanRenderer().renderSqlPlan(plan).sql
        kotlin.test.assertEquals(base, mine)
        assertContains(mine, "FROM ana.events events")
    }
}
