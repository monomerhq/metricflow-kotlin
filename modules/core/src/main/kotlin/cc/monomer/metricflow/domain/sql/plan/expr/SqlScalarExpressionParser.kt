package cc.monomer.metricflow.domain.sql.plan.expr

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.expression.AnyComparisonExpression
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.piped.FromQuery

/**
 * Validates a user-authored SQL fragment as one scalar expression.
 *
 * JSqlParser is used only as a maintained syntax/shape gate. The original text remains the
 * renderer input and no parsed AST is retained. `false` is intentional: JSqlParser then
 * rejects trailing input instead of accepting a valid prefix such as `amount + 1 trailing`.
 */
internal object SqlScalarExpressionParser {

    /**
     * Validate [sqlExpression] and return the column identifiers in its expression tree.
     *
     * Query-producing constructs are rejected even when JSqlParser can represent them as an
     * expression. This keeps semantic model expressions scalar and prevents a relation source
     * from being smuggled through an opaque SQL string.
     */
    fun referencedColumnNames(sqlExpression: String): Set<String> {
        if (sqlExpression.isBlank()) {
            throw IllegalArgumentException("SQL expression must not be blank")
        }
        val expression = try {
            CCJSqlParserUtil.parseExpression(sqlExpression, false)
        } catch (error: JSQLParserException) {
            throw IllegalArgumentException(
                "Invalid scalar SQL expression: ${error.message ?: sqlExpression}",
                error,
            )
        }
        val identifiers = LinkedHashSet<String>()
        expression.accept(ScalarExpressionVisitor(identifiers))
        return identifiers
    }

    private class ScalarExpressionVisitor(
        private val identifiers: MutableSet<String>,
    ) : ExpressionVisitorAdapter<Unit>() {
        override fun <S> visit(column: Column, context: S): Unit {
            identifiers.add(column.unquotedColumnName)
        }

        override fun <S> visit(function: Function, context: S): Unit {
            if (function.name.equals("TABLE", ignoreCase = true)) {
                reject(function)
            }
            super.visit(function, context)
        }

        override fun <S> visit(select: ParenthesedSelect, context: S): Unit = reject(select)

        override fun <S> visit(select: Select, context: S): Unit = reject(select)

        override fun <S> visit(existsExpression: ExistsExpression, context: S): Unit =
            reject(existsExpression)

        override fun <S> visit(anyComparisonExpression: AnyComparisonExpression, context: S): Unit =
            reject(anyComparisonExpression)

        override fun <S> visit(fromQuery: FromQuery, context: S): Unit = reject(fromQuery)

        private fun reject(node: Any): Nothing = throw IllegalArgumentException(
            "Relation-producing SQL expression is not allowed: ${node::class.simpleName}",
        )
    }
}
