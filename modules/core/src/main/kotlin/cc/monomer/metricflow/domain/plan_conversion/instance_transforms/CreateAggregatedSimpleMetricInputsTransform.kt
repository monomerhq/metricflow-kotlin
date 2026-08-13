package cc.monomer.metricflow.domain.plan_conversion.instance_transforms

import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSetTransform
import cc.monomer.metricflow.domain.dataflow.instance.SimpleMetricInputInstance
import cc.monomer.metricflow.domain.manifest.model.element.MeasureAggregationParameters
import cc.monomer.metricflow.domain.plan_conversion.helpers.SelectColumnSet
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlFunctionExpression

/**
 * Result of [CreateAggregatedSimpleMetricInputsTransform].
 *
 * Port of `metricflow.plan_conversion.instance_set_transforms.aggregated_simple_metric_input
 * .CreateAggregatedSimpleMetricInputsResult`.
 */
data class CreateAggregatedSimpleMetricInputsResult(
    val selectColumnSet: SelectColumnSet,
    val groupByColumnSet: SelectColumnSet,
)

/**
 * Create SELECT columns of the form `fct_bookings.bookings AS bookings` for every instance,
 * but convert simple-metric-input columns into `SUM(fct_bookings.bookings) AS bookings` (or
 * the appropriate aggregation function) so the resulting select can be used for aggregation.
 *
 * Port of `CreateAggregatedSimpleMetricInputsTransform`. Also assigns an output alias that
 * conforms to the resolved column-association name.
 */
class CreateAggregatedSimpleMetricInputsTransform(
    private val tableAlias: String,
    private val columnResolver: ColumnAssociationResolver,
    private val manifestObjectLookup: ManifestObjectLookup,
) : InstanceSetTransform<CreateAggregatedSimpleMetricInputsResult> {

    private val createSelectColumnTransform =
        CreateSelectColumnsForInstances(tableAlias = tableAlias, columnResolver = columnResolver)

    override fun transform(instanceSet: InstanceSet): CreateAggregatedSimpleMetricInputsResult {
        val instanceSetWithoutSimpleMetricInputs = instanceSet.withoutSimpleMetricInputs()
        val columnSetWithoutSimpleMetricInputs =
            createSelectColumnTransform.transform(instanceSetWithoutSimpleMetricInputs).selectColumnSet
        val groupByColumnSet =
            createSelectColumnTransform.transform(instanceSetWithoutSimpleMetricInputs).selectColumnSet
        val simpleMetricInputColumnSet = makeSqlColumnExpressionForAggregation(
            instanceSet.simpleMetricInputInstances,
        )

        return CreateAggregatedSimpleMetricInputsResult(
            selectColumnSet = columnSetWithoutSimpleMetricInputs.merge(simpleMetricInputColumnSet),
            groupByColumnSet = groupByColumnSet,
        )
    }

    private fun makeSqlColumnExpressionForAggregation(
        instances: List<SimpleMetricInputInstance>,
    ): SelectColumnSet {
        val outputColumns = instances.map { instance ->
            makeAggregationSqlColumnExpression(instance = instance, outputSpec = instance.spec)
        }
        return SelectColumnSet.ofSimpleMetricInputs(outputColumns)
    }

    private fun makeAggregationSqlColumnExpression(
        instance: SimpleMetricInputInstance,
        outputSpec: SimpleMetricInputSpec,
    ): SqlSelectColumn {
        // Column name of the simple-metric input in the table we're reading from.
        val columnNameInTable = instance.associatedColumn.columnName

        // Aggregation function and parameters from the metric definition.
        val simpleMetricInput = manifestObjectLookup.simpleMetricNameToInput.getValue(instance.spec.elementName)

        val readExpression = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(tableAlias = tableAlias, columnName = columnNameInTable),
            shouldRenderTableAlias = true,
        )

        val aggParams = simpleMetricInput.aggParams?.let {
            MeasureAggregationParameters(
                percentile = it.percentile,
                useDiscretePercentile = it.useDiscretePercentile,
                useApproximatePercentile = it.useApproximatePercentile,
            )
        }
        val aggregationExpression = SqlFunctionExpression.buildExpressionFromAggregationType(
            aggregationType = simpleMetricInput.agg,
            sqlColumnExpression = readExpression,
            aggParams = aggParams,
        )

        val aggregatedColumnName = columnResolver.resolveSpec(outputSpec).columnName
        return SqlSelectColumn(
            expr = aggregationExpression,
            columnAlias = aggregatedColumnName,
        )
    }
}
