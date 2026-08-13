package cc.monomer.metricflow.domain.plan_conversion.helpers

import cc.monomer.metricflow.domain.sql.plan.expr.SqlAggregateFunctionExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.expr.SqlFunction

/**
 * Composition helpers for building SQL expressions during dataflow→SQL conversion.
 *
 * Port of `metricflow.plan_conversion.sql_expression_builders`. The Python module exposes
 * `make_coalesced_expr`; we keep the same name and signature.
 */
object SqlExpressionBuilder {

    /**
     * Make a coalesced expression of the given column from the given table aliases.
     *
     * Port of `metricflow.plan_conversion.sql_expression_builders.make_coalesced_expr`.
     *
     * Example:
     * ```
     * tableAliases = ["a", "b"]
     * columnAlias = "is_instant"
     *
     * → COALESCE(a.is_instant, b.is_instant)
     * ```
     *
     * A single alias short-circuits to a plain column reference (no COALESCE wrapper).
     */
    fun makeCoalescedExpr(tableAliases: List<String>, columnAlias: String): SqlExpressionNode {
        if (tableAliases.size == 1) {
            return SqlColumnReferenceExpression.create(
                colRef = SqlColumnReference(tableAlias = tableAliases[0], columnName = columnAlias),
                shouldRenderTableAlias = true,
            )
        }
        val columnsToCoalesce: List<SqlExpressionNode> = tableAliases.map { tableAlias ->
            SqlColumnReferenceExpression.create(
                colRef = SqlColumnReference(tableAlias = tableAlias, columnName = columnAlias),
                shouldRenderTableAlias = true,
            )
        }
        return SqlAggregateFunctionExpression.create(
            sqlFunction = SqlFunction.COALESCE,
            sqlFunctionArgs = columnsToCoalesce,
        )
    }
}
