package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.domain.sql.render.SqlPlanRenderer
import java.util.EnumMap

/**
 * Explicit renderer composition supplied by an in-process engine consumer.
 *
 * The engine owns planning but does not own a warehouse dialect. Keeping this
 * registry at the seam means a consumer brings only the renderer artifacts it
 * serves; the engine artifact itself has no renderer or transport dependency.
 */
interface SqlPlanRendererRegistry {
    /** Returns the renderer registered for [dialect], or fails with a useful error. */
    fun rendererFor(dialect: SqlEngine): SqlPlanRenderer

    companion object {
        /** Builds a registry from explicit dialect registrations. */
        fun of(vararg registrations: SqlPlanRendererRegistration): SqlPlanRendererRegistry =
            StaticSqlPlanRendererRegistry(registrations.asList())
    }
}

/** A dialect-to-renderer binding used to compose [SqlPlanRendererRegistry]. */
data class SqlPlanRendererRegistration(
    val dialect: SqlEngine,
    val renderer: SqlPlanRenderer,
)

private class StaticSqlPlanRendererRegistry(
    registrations: List<SqlPlanRendererRegistration>,
) : SqlPlanRendererRegistry {
    private val renderersByDialect: EnumMap<SqlEngine, SqlPlanRenderer> =
        EnumMap<SqlEngine, SqlPlanRenderer>(SqlEngine::class.java).apply {
            registrations.forEach { registration ->
                check(put(registration.dialect, registration.renderer) == null) {
                    "A renderer is already registered for ${registration.dialect}"
                }
            }
        }

    override fun rendererFor(dialect: SqlEngine): SqlPlanRenderer =
        renderersByDialect[dialect]
            ?: error(
                "No SQL renderer is registered for $dialect. " +
                    "Add the matching metricflow-render-* artifact and register it explicitly.",
            )
}
