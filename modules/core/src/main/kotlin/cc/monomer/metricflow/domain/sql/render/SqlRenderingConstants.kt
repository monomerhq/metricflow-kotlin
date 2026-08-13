package cc.monomer.metricflow.domain.sql.render

/**
 * Constants used for rendering SQL.
 *
 * Port of `metricflow.sql.render.rendering_constants.SqlRenderingConstants`.
 */
object SqlRenderingConstants {

    /**
     * The indentation prefix applied to nested SQL fragments. e.g.
     *
     * ```
     * SELECT
     *   foo
     *   , bar
     * ```
     */
    const val INDENT: String = "  "
}
