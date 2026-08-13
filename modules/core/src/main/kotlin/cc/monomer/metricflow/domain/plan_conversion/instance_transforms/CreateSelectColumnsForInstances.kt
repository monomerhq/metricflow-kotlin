package cc.monomer.metricflow.domain.plan_conversion.instance_transforms

import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSetTransform
import cc.monomer.metricflow.domain.dataflow.instance.MdoInstance
import cc.monomer.metricflow.domain.plan_conversion.helpers.SelectColumnSet
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.OutputColumnOrderer
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression

/**
 * Result of [CreateSelectColumnsForInstances]. Carries both the bucketed [SelectColumnSet] and
 * the per-spec column mapping used by [OutputColumnOrderer]s.
 *
 * Port of `metricflow.plan_conversion.instance_set_transforms.select_columns.CreateSelectColumnsResult`.
 */
data class CreateSelectColumnsResult(
    val selectColumnSet: SelectColumnSet,
    /** Spec → columns. Kept as a [Map] to mirror Python's cached_property. */
    val specToColumnsMapping: Map<InstanceSpec, List<SqlSelectColumn>>,
) : Mergeable<CreateSelectColumnsResult> {

    override fun merge(other: CreateSelectColumnsResult): CreateSelectColumnsResult {
        val merged = LinkedHashMap<InstanceSpec, List<SqlSelectColumn>>(specToColumnsMapping)
        for ((spec, cols) in other.specToColumnsMapping) merged[spec] = cols
        return CreateSelectColumnsResult(
            selectColumnSet = selectColumnSet.merge(other.selectColumnSet),
            specToColumnsMapping = merged,
        )
    }

    /**
     * Get the resulting columns. If an [outputColumnOrderer] is supplied, the columns are
     * arranged according to that orderer; otherwise the canonical default order from
     * [SelectColumnSet.columnsInDefaultOrder] is used.
     */
    fun getColumns(outputColumnOrderer: OutputColumnOrderer?): List<SqlSelectColumn> {
        if (outputColumnOrderer != null) return outputColumnOrderer.orderColumns(specToColumnsMapping)
        return selectColumnSet.columnsInDefaultOrder
    }

    companion object {
        /** Empty result — used as the [Mergeable] identity. */
        val EMPTY: CreateSelectColumnsResult = CreateSelectColumnsResult(
            selectColumnSet = SelectColumnSet.EMPTY,
            specToColumnsMapping = emptyMap(),
        )
    }
}

/**
 * Create SELECT column expressions for every instance in the set.
 *
 * Port of `CreateSelectColumnsForInstances`. Assumes the columns the instances are stored under
 * are described by [columnAssociationResolver] and live under [tableAlias].
 *
 * The Python class accepts an optional `output_to_input_column_mapping` (used when a wrapping
 * SELECT renames input columns). The Kotlin form takes the same mapping via the
 * [outputToInputColumnMapping] constructor argument; pass [emptyMap] when no remapping is
 * needed (the "no default parameter values" rule keeps the contract explicit at call sites).
 */
class CreateSelectColumnsForInstances(
    private val tableAlias: String,
    private val columnResolver: ColumnAssociationResolver,
    private val outputToInputColumnMapping: Map<String, String>,
) : InstanceSetTransform<CreateSelectColumnsResult> {

    /** Convenience: no input→output remapping. */
    constructor(tableAlias: String, columnResolver: ColumnAssociationResolver) : this(
        tableAlias = tableAlias,
        columnResolver = columnResolver,
        outputToInputColumnMapping = emptyMap(),
    )

    override fun transform(instanceSet: InstanceSet): CreateSelectColumnsResult {
        val specToColumns = LinkedHashMap<InstanceSpec, List<SqlSelectColumn>>()

        val metricCols = mutableListOf<SqlSelectColumn>()
        for (metricInstance in instanceSet.metricInstances) {
            val columns = makeSqlColumnExpression(metricInstance)
            metricCols.addAll(columns)
            val simplified = MetricSpec.create(
                elementName = metricInstance.spec.elementName,
                whereFilterSpecs = emptyList(),
                alias = metricInstance.spec.alias,
                offsetWindow = null,
                offsetToGrain = null,
            )
            specToColumns[simplified] = columns
        }

        val simpleMetricInputCols = mutableListOf<SqlSelectColumn>()
        for (instance in instanceSet.simpleMetricInputInstances) {
            val columns = makeSqlColumnExpression(instance)
            simpleMetricInputCols.addAll(columns)
            specToColumns[instance.spec] = columns
        }

        val dimensionCols = mutableListOf<SqlSelectColumn>()
        for (instance in instanceSet.dimensionInstances) {
            val columns = makeSqlColumnExpression(instance)
            dimensionCols.addAll(columns)
            specToColumns[instance.spec] = columns
        }

        val timeDimensionCols = mutableListOf<SqlSelectColumn>()
        for (instance in instanceSet.timeDimensionInstances) {
            val columns = makeSqlColumnExpression(instance)
            timeDimensionCols.addAll(columns)
            specToColumns[instance.spec] = columns
        }

        val entityCols = mutableListOf<SqlSelectColumn>()
        for (instance in instanceSet.entityInstances) {
            val columns = makeSqlColumnExpression(instance)
            entityCols.addAll(columns)
            specToColumns[instance.spec] = columns
        }

        val metadataCols = mutableListOf<SqlSelectColumn>()
        for (instance in instanceSet.metadataInstances) {
            val columns = makeSqlColumnExpression(instance)
            metadataCols.addAll(columns)
            specToColumns[instance.spec] = columns
        }

        val groupByMetricCols = mutableListOf<SqlSelectColumn>()
        for (instance in instanceSet.groupByMetricInstances) {
            val columns = makeSqlColumnExpression(instance)
            groupByMetricCols.addAll(columns)
            specToColumns[instance.spec] = columns
        }

        return CreateSelectColumnsResult(
            selectColumnSet = SelectColumnSet.create(
                metricColumns = metricCols,
                simpleMetricInputColumns = simpleMetricInputCols,
                dimensionColumns = dimensionCols,
                timeDimensionColumns = timeDimensionCols,
                entityColumns = entityCols,
                groupByMetricColumns = groupByMetricCols,
                metadataColumns = metadataCols,
            ),
            specToColumnsMapping = specToColumns,
        )
    }

    private fun makeSqlColumnExpression(elementInstance: MdoInstance): List<SqlSelectColumn> {
        // Sanity check: ensure a 1:1 mapping between the columns we'd resolve from the spec and
        // the columns already stored on the instance.
        val expectedColumnAssociations = listOf(columnResolver.resolveSpec(elementInstance.spec))
        val existingColumnAssociations = elementInstance.associatedColumns

        val columnMatches = LinkedHashMap<String, List<String>>()
        for (expected in expectedColumnAssociations) {
            val match = existingColumnAssociations
                .filter { it.columnCorrelationKey == expected.columnCorrelationKey }
                .map { it.columnName }
            columnMatches[expected.columnName] = match
        }
        check(columnMatches.values.all { it.size == 1 }) {
            "Did not find exactly one match for each expected column associations. " +
                "Expected -> existing mappings: $columnMatches"
        }
        val existingNames = existingColumnAssociations.map { it.columnName }.toSet()
        val mappedNames = columnMatches.values.flatten().toSet()
        check(existingNames == mappedNames) {
            "Not all existing columns were mapped. Existing: $existingNames. Mapped: $mappedNames, " +
                "expected=$expectedColumnAssociations existing=$existingColumnAssociations"
        }

        return columnMatches.entries.map { (expectedName, mappedCols) ->
            val resolvedInputName = outputToInputColumnMapping[expectedName] ?: mappedCols[0]
            SqlSelectColumn(
                expr = SqlColumnReferenceExpression.create(
                    colRef = SqlColumnReference(tableAlias = tableAlias, columnName = resolvedInputName),
                    shouldRenderTableAlias = true,
                ),
                columnAlias = expectedName,
            )
        }
    }
}

/**
 * Build SELECT columns for instance sets coming from multiple tables.
 *
 * Port of the top-level Python function
 * `create_simple_select_columns_for_instance_sets`. Used when joining multiple datasets and
 * rendering a top-level SELECT that exposes columns from each input.
 */
fun createSimpleSelectColumnsForInstanceSets(
    columnResolver: ColumnAssociationResolver,
    tableAliasToInstanceSet: Map<String, InstanceSet>,
): List<SqlSelectColumn> {
    var columnSet: SelectColumnSet = SelectColumnSet.EMPTY
    for ((tableAlias, instanceSet) in tableAliasToInstanceSet) {
        columnSet = columnSet.merge(
            instanceSet.transform(
                CreateSelectColumnsForInstances(tableAlias = tableAlias, columnResolver = columnResolver),
            ).selectColumnSet,
        )
    }
    return columnSet.columnsInDefaultOrder
}
