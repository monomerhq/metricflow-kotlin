package cc.monomer.metricflow.domain.dataflow.dataset

import cc.monomer.metricflow.common.dag.DynamicIdPrefix
import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.common.time.TimeSpineSource
import cc.monomer.metricflow.domain.dataflow.instance.DimensionInstance
import cc.monomer.metricflow.domain.dataflow.instance.EntityInstance
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.instance.SimpleMetricInputInstance
import cc.monomer.metricflow.domain.dataflow.instance.TimeDimensionInstance
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.lookup.SemanticModelHelper
import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.spec.AggregationState
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.DEFAULT_TIME_GRANULARITY
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * Aggregate of the [DimensionInstance]s, [TimeDimensionInstance]s, and the
 * [SqlSelectColumn]s that materialise them. Returned by the dimension-conversion phase
 * of [SemanticModelToDataSetConverter].
 *
 * Port of `metricflow.dataset.convert_semantic_model.DimensionConversionResult`.
 */
data class DimensionConversionResult(
    val dimensionInstances: List<DimensionInstance>,
    val timeDimensionInstances: List<TimeDimensionInstance>,
    val selectColumns: List<SqlSelectColumn>,
)

/**
 * Converts a semantic model defined in the manifest into a [SemanticModelDataSet] that
 * can be consumed by the dataflow plan builder.
 *
 * Port of `metricflow.dataset.convert_semantic_model.SemanticModelToDataSetConverter`.
 *
 * Entity links generally refer to the entities used to join the simple-metric source to
 * the dimension source. For example, the dimension name `user_id__device_id__platform`
 * has entity links `(user_id, device_id)`. The converter produces a copy of each
 * dimension / entity per legal entity link so the planner can resolve a request like
 * `user_id__device_id__platform` without re-joining the source semantic model.
 */
class SemanticModelToDataSetConverter(
    private val columnAssociationResolver: ColumnAssociationResolver,
    private val manifestLookup: SemanticManifestLookup,
    private val manifestObjectLookup: ManifestObjectLookup,
) {

    /**
     * Convert the supplied [modelReference] into a [SemanticModelDataSet]. The dataset
     * includes every simple-metric input on the model plus every dimension / entity
     * exposed via the model's local entity links.
     */
    fun createSqlSourceDataSet(modelReference: SemanticModelReference): SemanticModelDataSet {
        val allSimpleMetricInputInstances = mutableListOf<SimpleMetricInputInstance>()
        val allDimensionInstances = mutableListOf<DimensionInstance>()
        val allTimeDimensionInstances = mutableListOf<TimeDimensionInstance>()
        val allEntityInstances = mutableListOf<EntityInstance>()
        val allSelectColumns = mutableListOf<SqlSelectColumn>()

        val modelName = modelReference.semanticModelName
        val fromSourceAlias = SequentialIdGenerator
            .createNextId(DynamicIdPrefix(prefix = "${modelName}_src"))
            .strValue

        // Simple metrics on this model (if any).
        val modelLookup = manifestObjectLookup
            .modelIdToSimpleMetricModelLookup[SemanticModelId.getInstance(modelReference.semanticModelName)]
        if (modelLookup != null) {
            for ((_, simpleMetricInputs) in modelLookup.aggregationConfigurationToSimpleMetricInputs) {
                for (input in simpleMetricInputs) {
                    val spec = SimpleMetricInputSpec(
                        elementName = input.name,
                        fillNullsWith = null,
                    )
                    val instance = SimpleMetricInputInstance(
                        associatedColumns = listOf(columnAssociationResolver.resolveSpec(spec)),
                        spec = spec,
                        definedFrom = listOf(
                            SemanticModelElementReference(
                                semanticModelName = modelLookup.modelId.modelName,
                                elementName = input.name,
                            ),
                        ),
                        aggregationState = AggregationState.NON_AGGREGATED,
                    )
                    allSimpleMetricInputInstances.add(instance)
                    allSelectColumns.add(
                        SqlSelectColumn(
                            expr = makeElementSqlExpr(
                                tableAlias = fromSourceAlias,
                                elementName = input.name,
                                elementExpr = input.expr,
                            ),
                            columnAlias = instance.associatedColumn.columnName,
                        ),
                    )
                }
            }
        }

        // Group-by items in the semantic model can be accessed via any local entity link.
        val possibleEntityLinks = mutableListOf<List<EntityReference>>(emptyList())
        val semanticModel = manifestLookup.semanticModelLookup.getByReference(modelReference)
            ?: throw MetricFlowInternalError(
                "Did not find a semantic model with the given reference: modelReference=$modelReference",
            )
        for (entityLink in SemanticModelHelper.entityLinksForLocalElements(semanticModel)) {
            possibleEntityLinks.add(listOf(entityLink))
        }

        // Dimensions — emitted per entity-link variant.
        val conversionResults = possibleEntityLinks.map { entityLinks ->
            convertDimensions(
                semanticModelName = semanticModel.name,
                dimensions = semanticModel.dimensions,
                entityLinks = entityLinks,
                tableAlias = fromSourceAlias,
            )
        }
        for (result in conversionResults) {
            allDimensionInstances.addAll(result.dimensionInstances)
            allTimeDimensionInstances.addAll(result.timeDimensionInstances)
            allSelectColumns.addAll(result.selectColumns)
        }

        // Entities — also emitted per entity-link variant.
        for (entityLinks in possibleEntityLinks) {
            val (entityInstances, entitySelectColumns) = createEntityInstances(
                semanticModelName = semanticModel.name,
                entities = semanticModel.entities,
                entityLinks = entityLinks,
                tableAlias = fromSourceAlias,
            )
            allEntityInstances.addAll(entityInstances)
            allSelectColumns.addAll(entitySelectColumns)
        }

        // Python's `NodeRelation.__create_default_relation_name` Pydantic validator
        // auto-builds `relation_name` from `db + schema + alias` when the JSON omits it. The
        // Kotlin manifest model (W1) explicitly skipped that quirk; we reconstruct the
        // relation name on demand here so the converter handles bare `schema/alias`-style
        // manifests (e.g. the `minimal_valid_manifest`) without a manifest-model rewrite.
        val relationName = semanticModel.nodeRelation.relationName.takeIf { it.isNotEmpty() }
            ?: buildString {
                semanticModel.nodeRelation.database?.takeIf { it.isNotEmpty() }?.let {
                    append(it); append('.')
                }
                append(semanticModel.nodeRelation.schemaName)
                append('.')
                append(semanticModel.nodeRelation.alias)
            }
        val fromSource = SqlTableNode.create(
            sqlTable = SqlTable.fromString(relationName),
        )

        val selectStatement = SqlSelectStatementNode.create(
            description = "Read Elements From Semantic Model '${semanticModel.name}'",
            selectColumns = allSelectColumns,
            fromSource = fromSource,
            fromSourceAlias = fromSourceAlias,
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )

        return SemanticModelDataSet(
            semanticModelReference = SemanticModelReference(semanticModelName = semanticModel.name),
            instanceSet = InstanceSet(
                simpleMetricInputInstances = allSimpleMetricInputInstances.toList(),
                dimensionInstances = allDimensionInstances.toList(),
                timeDimensionInstances = allTimeDimensionInstances.toList(),
                entityInstances = allEntityInstances.toList(),
                groupByMetricInstances = emptyList(),
                metricInstances = emptyList(),
                metadataInstances = emptyList(),
            ),
            sqlSelectNode = selectStatement,
        )
    }

    /**
     * Build the dataset for a [TimeSpineSource] — the calendar table the engine joins
     * against for time-spine operations like cumulative metrics and time offsets.
     *
     * Port of `SemanticModelToDataSetConverter.build_time_spine_source_data_set`.
     */
    fun buildTimeSpineSourceDataSet(timeSpineSource: TimeSpineSource): SqlDataSet {
        val fromSourceAlias = SequentialIdGenerator
            .createNextId(StaticIdPrefix.TIME_SPINE_SOURCE)
            .strValue
        val baseGranularity = timeSpineSource.baseGranularity
        val baseColumnName = timeSpineSource.baseColumn

        val timeDimensionInstances = mutableListOf<TimeDimensionInstance>()
        val selectColumns = mutableListOf<SqlSelectColumn>()

        // Base granularity instance + select column.
        val baseTimeDimensionInstance = createTimeDimensionInstance(
            elementName = baseColumnName,
            entityLinks = emptyList(),
            timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(baseGranularity),
            datePart = null,
            semanticModelName = null,
        )
        timeDimensionInstances.add(baseTimeDimensionInstance)
        val baseDimensionSelectExpr = SqlColumnReferenceExpression.fromColumnReference(
            tableAlias = fromSourceAlias,
            columnName = baseColumnName,
        )
        selectColumns.add(
            SqlSelectColumn(
                expr = baseDimensionSelectExpr,
                columnAlias = baseTimeDimensionInstance.associatedColumn.columnName,
            ),
        )

        val (additionalInstances, additionalColumns) = buildTimeDimensionInstancesAndColumns(
            definedTimeGranularity = baseGranularity,
            elementName = baseColumnName,
            entityLinks = emptyList(),
            dimensionSelectExpr = baseDimensionSelectExpr,
            semanticModelName = null,
        )
        timeDimensionInstances.addAll(additionalInstances)
        selectColumns.addAll(additionalColumns)

        // Custom granularity instances + select columns.
        for (customGranularity in timeSpineSource.customGranularities) {
            val customInstance = createTimeDimensionInstance(
                // Use base column name so MetricTimeDimensionTransformNode can recognise this as
                // metric_time-compatible.
                elementName = baseColumnName,
                entityLinks = emptyList(),
                timeGranularity = ExpandedTimeGranularity(
                    name = customGranularity.name,
                    baseGranularity = baseGranularity,
                ),
                datePart = null,
                semanticModelName = null,
            )
            timeDimensionInstances.add(customInstance)
            selectColumns.add(
                SqlSelectColumn(
                    expr = makeElementSqlExpr(
                        tableAlias = fromSourceAlias,
                        elementName = customGranularity.parsedColumnName,
                        elementExpr = null,
                    ),
                    columnAlias = customInstance.associatedColumn.columnName,
                ),
            )
        }

        return SqlDataSet(
            instanceSet = InstanceSet(
                simpleMetricInputInstances = emptyList(),
                dimensionInstances = emptyList(),
                timeDimensionInstances = timeDimensionInstances.toList(),
                entityInstances = emptyList(),
                groupByMetricInstances = emptyList(),
                metricInstances = emptyList(),
                metadataInstances = emptyList(),
            ),
            sqlSelectNode = SqlSelectStatementNode.create(
                description = timeSpineSource.dataSetDescription,
                selectColumns = selectColumns,
                fromSource = SqlTableNode.create(sqlTable = timeSpineSource.sqlTable),
                fromSourceAlias = fromSourceAlias,
                cteSources = emptyList(),
                joinDescs = emptyList(),
                groupBys = emptyList(),
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )
    }

    private fun createDimensionInstance(
        semanticModelName: String,
        dimension: Dimension,
        entityLinks: List<EntityReference>,
    ): DimensionInstance {
        val dimensionSpec = DimensionSpec(
            elementName = dimension.reference.elementName,
            entityLinks = entityLinks,
            alias = null,
        )
        return DimensionInstance(
            associatedColumns = listOf(columnAssociationResolver.resolveSpec(dimensionSpec)),
            spec = dimensionSpec,
            definedFrom = listOf(
                SemanticModelElementReference(
                    semanticModelName = semanticModelName,
                    elementName = dimension.reference.elementName,
                ),
            ),
        )
    }

    private fun createTimeDimensionInstance(
        elementName: String,
        entityLinks: List<EntityReference>,
        timeGranularity: ExpandedTimeGranularity?,
        datePart: DatePart?,
        semanticModelName: String?,
    ): TimeDimensionInstance {
        val timeDimensionSpec = TimeDimensionSpec(
            elementName = elementName,
            entityLinks = entityLinks,
            timeGranularity = timeGranularity,
            datePart = datePart,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        )
        val definedFrom = if (semanticModelName != null) {
            listOf(
                SemanticModelElementReference(
                    semanticModelName = semanticModelName,
                    elementName = elementName,
                ),
            )
        } else {
            emptyList()
        }
        return TimeDimensionInstance(
            associatedColumns = listOf(columnAssociationResolver.resolveSpec(timeDimensionSpec)),
            spec = timeDimensionSpec,
            definedFrom = definedFrom,
        )
    }

    private fun createEntityInstance(
        semanticModelName: String,
        entity: Entity,
        entityLinks: List<EntityReference>,
    ): EntityInstance {
        val entitySpec = EntitySpec(
            elementName = entity.reference.elementName,
            entityLinks = entityLinks,
            alias = null,
        )
        return EntityInstance(
            associatedColumns = listOf(columnAssociationResolver.resolveSpec(entitySpec)),
            spec = entitySpec,
            definedFrom = listOf(
                SemanticModelElementReference(
                    semanticModelName = semanticModelName,
                    elementName = entity.reference.elementName,
                ),
            ),
        )
    }

    private fun convertDimensions(
        semanticModelName: String,
        dimensions: List<Dimension>,
        entityLinks: List<EntityReference>,
        tableAlias: String,
    ): DimensionConversionResult {
        val dimensionInstances = mutableListOf<DimensionInstance>()
        val timeDimensionInstances = mutableListOf<TimeDimensionInstance>()
        val selectColumns = mutableListOf<SqlSelectColumn>()

        for (dimension in dimensions) {
            val selectExpr = makeElementSqlExpr(
                tableAlias = tableAlias,
                elementName = dimension.reference.elementName,
                elementExpr = dimension.expr,
            )
            when (dimension.type) {
                DimensionType.CATEGORICAL -> {
                    val instance = createDimensionInstance(
                        semanticModelName = semanticModelName,
                        dimension = dimension,
                        entityLinks = entityLinks,
                    )
                    dimensionInstances.add(instance)
                    selectColumns.add(
                        SqlSelectColumn(
                            expr = selectExpr,
                            columnAlias = instance.associatedColumn.columnName,
                        ),
                    )
                }
                DimensionType.TIME -> {
                    val (derivedInstances, derivedColumns) = convertTimeDimension(
                        dimensionSelectExpr = selectExpr,
                        dimension = dimension,
                        semanticModelName = semanticModelName,
                        entityLinks = entityLinks,
                    )
                    timeDimensionInstances.addAll(derivedInstances)
                    selectColumns.addAll(derivedColumns)
                }
            }
        }

        return DimensionConversionResult(
            dimensionInstances = dimensionInstances,
            timeDimensionInstances = timeDimensionInstances,
            selectColumns = selectColumns,
        )
    }

    private fun convertTimeDimension(
        dimensionSelectExpr: SqlExpressionNode,
        dimension: Dimension,
        semanticModelName: String,
        entityLinks: List<EntityReference>,
    ): Pair<List<TimeDimensionInstance>, List<SqlSelectColumn>> {
        val timeDimensionInstances = mutableListOf<TimeDimensionInstance>()
        val selectColumns = mutableListOf<SqlSelectColumn>()

        val definedTimeGranularity = dimension.typeParams?.timeGranularity ?: DEFAULT_TIME_GRANULARITY

        val baseInstance = createTimeDimensionInstance(
            semanticModelName = semanticModelName,
            elementName = dimension.reference.elementName,
            entityLinks = entityLinks,
            timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(definedTimeGranularity),
            datePart = null,
        )
        timeDimensionInstances.add(baseInstance)

        // Validity-window dimensions are read directly without truncation, since the window
        // might be stored in seconds while we'd truncate to daily.
        if (dimension.validityParams != null) {
            selectColumns.add(
                SqlSelectColumn(
                    expr = dimensionSelectExpr,
                    columnAlias = baseInstance.associatedColumn.columnName,
                ),
            )
        } else {
            selectColumns.add(
                buildColumnForStandardTimeGranularity(
                    timeGranularity = definedTimeGranularity,
                    expr = dimensionSelectExpr,
                    columnAlias = baseInstance.associatedColumn.columnName,
                ),
            )
        }

        val (additionalInstances, additionalColumns) = buildTimeDimensionInstancesAndColumns(
            definedTimeGranularity = definedTimeGranularity,
            elementName = dimension.reference.elementName,
            entityLinks = entityLinks,
            dimensionSelectExpr = dimensionSelectExpr,
            semanticModelName = semanticModelName,
        )
        timeDimensionInstances.addAll(additionalInstances)
        selectColumns.addAll(additionalColumns)

        return timeDimensionInstances to selectColumns
    }

    private fun buildTimeDimensionInstancesAndColumns(
        definedTimeGranularity: TimeGranularity,
        elementName: String,
        entityLinks: List<EntityReference>,
        dimensionSelectExpr: SqlExpressionNode,
        semanticModelName: String?,
    ): Pair<List<TimeDimensionInstance>, List<SqlSelectColumn>> {
        val instances = mutableListOf<TimeDimensionInstance>()
        val columns = mutableListOf<SqlSelectColumn>()

        // Larger-grain alternatives — DATE_TRUNC variants for downstream query resolution.
        for (timeGranularity in TimeGranularity.entries) {
            if (timeGranularity.toInt() > definedTimeGranularity.toInt()) {
                val instance = createTimeDimensionInstance(
                    semanticModelName = semanticModelName,
                    elementName = elementName,
                    entityLinks = entityLinks,
                    timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(timeGranularity),
                    datePart = null,
                )
                instances.add(instance)
                columns.add(
                    buildColumnForStandardTimeGranularity(
                        timeGranularity = timeGranularity,
                        expr = dimensionSelectExpr,
                        columnAlias = instance.associatedColumn.columnName,
                    ),
                )
            }
        }

        // Date-part alternatives — EXTRACT variants for query resolution.
        for (datePart in DatePart.entries) {
            if (datePart.toInt() >= definedTimeGranularity.toInt()) {
                val instance = createTimeDimensionInstance(
                    semanticModelName = semanticModelName,
                    elementName = elementName,
                    entityLinks = entityLinks,
                    timeGranularity = null,
                    datePart = datePart,
                )
                instances.add(instance)
                columns.add(
                    SqlSelectColumn(
                        expr = SqlExtractExpression.create(datePart = datePart, arg = dimensionSelectExpr),
                        columnAlias = instance.associatedColumn.columnName,
                    ),
                )
            }
        }

        return instances to columns
    }

    private fun buildColumnForStandardTimeGranularity(
        timeGranularity: TimeGranularity,
        expr: SqlExpressionNode,
        columnAlias: String,
    ): SqlSelectColumn = SqlSelectColumn(
        expr = SqlDateTruncExpression.create(timeGranularity = timeGranularity, arg = expr),
        columnAlias = columnAlias,
    )

    private fun createEntityInstances(
        semanticModelName: String,
        entities: List<Entity>,
        entityLinks: List<EntityReference>,
        tableAlias: String,
    ): Pair<List<EntityInstance>, List<SqlSelectColumn>> {
        val instances = mutableListOf<EntityInstance>()
        val columns = mutableListOf<SqlSelectColumn>()
        for (entity in entities) {
            // Skip user_id__user_id-shaped duplicates: an entity linked to itself is no
            // additional information.
            if (entityLinks.size == 1 && entity.reference == entityLinks[0]) continue

            val instance = createEntityInstance(
                semanticModelName = semanticModelName,
                entity = entity,
                entityLinks = entityLinks,
            )
            instances.add(instance)
            columns.add(
                SqlSelectColumn(
                    expr = makeElementSqlExpr(
                        tableAlias = tableAlias,
                        elementName = entity.reference.elementName,
                        elementExpr = entity.expr,
                    ),
                    columnAlias = instance.associatedColumn.columnName,
                ),
            )
        }
        return instances to columns
    }

    companion object {
        /** Regex used to decide whether a free-form expression is a bare column identifier. */
        private val SQL_IDENTIFIER_REGEX: Regex = Regex("^[a-zA-Z_][a-zA-Z_0-9]*$")

        /**
         * Build the SQL expression for reading [elementName] from the source.
         *
         * If [elementExpr] is null, the column name is [elementName] itself. If [elementExpr]
         * looks like a bare identifier (and isn't a SQL reserved word like TRUE/FALSE/NULL),
         * we emit a column reference; otherwise we emit a raw SQL string expression so the
         * configured expression (e.g. `CAST(x AS DATE)`) is preserved.
         */
        @JvmStatic
        fun makeElementSqlExpr(
            tableAlias: String,
            elementName: String,
            elementExpr: String?,
        ): SqlExpressionNode {
            if (elementExpr != null) {
                val isIdentifier = SQL_IDENTIFIER_REGEX.matches(elementExpr) &&
                    elementExpr.uppercase() !in RESERVED_LITERAL_TOKENS
                return if (isIdentifier) {
                    SqlColumnReferenceExpression.create(
                        colRef = SqlColumnReference(
                            tableAlias = tableAlias,
                            columnName = elementExpr,
                        ),
                        shouldRenderTableAlias = true,
                    )
                } else {
                    SqlStringExpression.create(
                        sqlExpr = elementExpr,
                        bindParameterSet = cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet(),
                        requiresParenthesis = true,
                        usedColumns = null,
                    )
                }
            }
            return SqlColumnReferenceExpression.create(
                colRef = SqlColumnReference(
                    tableAlias = tableAlias,
                    columnName = elementName,
                ),
                shouldRenderTableAlias = true,
            )
        }

        private val RESERVED_LITERAL_TOKENS: Set<String> = setOf("TRUE", "FALSE", "NULL")
    }
}
