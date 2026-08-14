package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.sql.render.DefaultDialectSqlPlanRenderer
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SqlPlanRendererRegistryTest {
    @Test
    fun `explicit registration returns the selected renderer`() {
        val renderer = DefaultDialectSqlPlanRenderer()
        val registry = SqlPlanRendererRegistry.of(
            SqlPlanRendererRegistration(SqlEngine.POSTGRES, renderer),
        )

        assertSame(renderer, registry.rendererFor(SqlEngine.POSTGRES))
    }

    @Test
    fun `missing registration names the dialect and artifact seam`() {
        val exception = assertFailsWith<IllegalStateException> {
            SqlPlanRendererRegistry.of().rendererFor(SqlEngine.TRINO)
        }

        kotlin.test.assertTrue(exception.message.orEmpty().contains("TRINO"))
        kotlin.test.assertTrue(exception.message.orEmpty().contains("metricflow-render-*"))
    }

    @Test
    fun `duplicate dialect registration fails during composition`() {
        val renderer = DefaultDialectSqlPlanRenderer()
        assertFailsWith<IllegalStateException> {
            SqlPlanRendererRegistry.of(
                SqlPlanRendererRegistration(SqlEngine.POSTGRES, renderer),
                SqlPlanRendererRegistration(SqlEngine.POSTGRES, renderer),
            )
        }
    }
}
