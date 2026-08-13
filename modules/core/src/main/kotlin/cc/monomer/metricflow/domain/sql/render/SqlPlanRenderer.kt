package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor

/**
 * Renders an [SqlPlan] (or any [SqlPlanNode] subtree) to SQL.
 *
 * Port of `metricflow.sql.render.sql_plan_renderer.SqlPlanRenderer`. The interface is a
 * specialised [SqlPlanNodeVisitor] returning [SqlPlanRenderResult]. The expression-level
 * rendering is delegated to a [SqlExpressionRenderer], exposed via [exprRenderer] so dialect
 * subclasses may share a single expression renderer across plan-renderer instances.
 */
interface SqlPlanRenderer : SqlPlanNodeVisitor<SqlPlanRenderResult> {

    /** Render the given full plan, starting from its render node. */
    fun renderSqlPlan(sqlQueryPlan: SqlPlan): SqlPlanRenderResult = renderNode(sqlQueryPlan.renderNode)

    /** Render a single subtree directly — useful for tests and internal recursion. */
    fun renderNode(node: SqlPlanNode): SqlPlanRenderResult = node.accept(this)

    /** The expression renderer used to render SQL expressions inside the plan. */
    val exprRenderer: SqlExpressionRenderer
}
