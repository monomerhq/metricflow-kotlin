package cc.monomer.metricflow.domain.plan_conversion.to_sql_plan

import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.common.time.TimeSpineSource
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.dataflow.dataset.AnnotatedSqlDataSet
import cc.monomer.metricflow.domain.dataflow.dataset.SqlDataSet
import cc.monomer.metricflow.domain.dataflow.instance.DimensionInstance
import cc.monomer.metricflow.domain.dataflow.instance.EntityInstance
import cc.monomer.metricflow.domain.dataflow.instance.GroupByMetricInstance
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.instance.LinkableInstance
import cc.monomer.metricflow.domain.dataflow.instance.MdoInstance
import cc.monomer.metricflow.domain.dataflow.instance.MetadataInstance
import cc.monomer.metricflow.domain.dataflow.instance.MetricInstance
import cc.monomer.metricflow.domain.dataflow.instance.SimpleMetricInputInstance
import cc.monomer.metricflow.domain.dataflow.instance.TimeDimensionInstance
import cc.monomer.metricflow.domain.dataflow.nodes.AddGeneratedUuidColumnNode
import cc.monomer.metricflow.domain.dataflow.nodes.AggregateSimpleMetricInputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.AliasSpecsNode
import cc.monomer.metricflow.domain.dataflow.nodes.CombineAggregatedOutputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ComputeMetricsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ConstrainTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinConversionEventsNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOnEntitiesNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOverTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinToCustomGranularityNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinToTimeSpineNode
import cc.monomer.metricflow.domain.dataflow.nodes.MetricTimeDimensionTransformNode
import cc.monomer.metricflow.domain.dataflow.nodes.MinMaxNode
import cc.monomer.metricflow.domain.dataflow.nodes.OffsetBaseGrainByCustomGrainNode
import cc.monomer.metricflow.domain.dataflow.nodes.OffsetCustomGranularityNode
import cc.monomer.metricflow.domain.dataflow.nodes.OrderByLimitNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.dataflow.nodes.SelectorNode
import cc.monomer.metricflow.domain.dataflow.nodes.SemiAdditiveJoinNode
import cc.monomer.metricflow.domain.dataflow.nodes.WhereFilterNode
import cc.monomer.metricflow.domain.dataflow.nodes.WindowReaggregationNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultDataTableNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultTableNode
import cc.monomer.metricflow.domain.dataflow.support.NullFillValueMapping
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.ConversionCalculationType
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.references.MetricModelReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.plan_conversion.helpers.SelectColumnSet
import cc.monomer.metricflow.domain.plan_conversion.helpers.SqlPlanJoinBuilder
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.AddGroupByMetric
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.AddMetadata
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.AddMetrics
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.ChangeAssociatedColumns
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.ChangeSimpleMetricInputAggregationState
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.ConvertToMetadata
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.CreateAggregatedSimpleMetricInputsTransform
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.CreateSelectColumnForCombineOutputNode
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.CreateSelectColumnsForInstances
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.FilterLinkableInstancesWithLeadingLink
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.RemoveMetrics
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.RemoveSimpleMetricInputTransform
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.SelectElementsTransform
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.UpdateSimpleMetricInputFillNullsWith
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.createSimpleSelectColumnsForInstanceSets
import cc.monomer.metricflow.domain.plan_conversion.spec_transforms.CreateColumnAssociations
import cc.monomer.metricflow.domain.plan_conversion.spec_transforms.CreateSelectCoalescedColumnsForLinkableSpecs
import cc.monomer.metricflow.domain.plan_conversion.spec_transforms.SelectOnlyLinkableSpecs
import cc.monomer.metricflow.domain.spec.AggregationState
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.GroupByMetricSpec
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.MetadataSpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec
import cc.monomer.metricflow.domain.sql.plan.ColumnAliasRenamer
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlBetweenExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAddTimeExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlArithmeticExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlArithmeticOperator
import cc.monomer.metricflow.domain.sql.plan.expr.SqlCaseExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlComparison
import cc.monomer.metricflow.domain.sql.plan.expr.SqlComparisonExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlFunction
import cc.monomer.metricflow.domain.sql.plan.expr.SqlFunctionExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIntegerExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlLogicalExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlLogicalOperator
import cc.monomer.metricflow.domain.sql.plan.expr.SqlNullExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlRatioComputationExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringLiteralExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlSubtractTimeIntervalExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowFunction
import cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowFunctionExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowOrderByArgument
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlJoinDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlOrderByDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import java.time.LocalDateTime

/**
 * Converts a single [DataflowPlanNode] into a [SqlDataSet] — the central engine of the
 * dataflow→SQL conversion layer.
 *
 * Port of `metricflow.plan_conversion.to_sql_plan.dataflow_to_subquery
 * .DataflowNodeToSqlSubqueryVisitor` (Python ~2.4k LOC). Each `visit*` override knows how to
 * realise one dataflow node type as a sub-SELECT or table reference. The visitor walks the
 * dataflow DAG bottom-up: every parent node is converted first via [getOutputDataSet], the
 * result is wrapped into a `SqlSelectStatementNode` that joins/filters/aggregates as required,
 * and the new [SqlDataSet] is cached so multi-parent dataflow nodes don't re-emit common
 * branches.
 *
 * ## Status — W15 time-spine runtime port
 *
 * W13/W14 filled the leaf, projection, aggregation, combination, semi-additive, and window
 * reaggregation methods. W15 fills the cumulative time-range join, standard/custom time-spine
 * joins, conversion-event deduplication, and both custom-granularity offset pipelines. The
 * visitor builds those SQL plans from the manifest-owned [TimeSpineSource] metadata and the
 * existing SQL AST; warehouse execution remains outside this repository.
 *
 * @property columnAssociationResolver How instance specs map to SQL column names.
 * @property semanticManifestLookup The W7a/W7c lookup composition root.
 * @property outputColumnOrderer Optional orderer for the final SELECT projection.
 */
class DataflowNodeToSqlSubqueryVisitor(
    val columnAssociationResolver: ColumnAssociationResolver,
    val semanticManifestLookup: SemanticManifestLookup,
    val outputColumnOrderer: OutputColumnOrderer?,
) : DataflowPlanNodeVisitor<SqlDataSet> {

    private val metricLookup = semanticManifestLookup.metricLookup
    private val nodeToOutputDataSet: MutableMap<DataflowPlanNode, SqlDataSet> = LinkedHashMap()

    /**
     * Convert [dataflowPlanNode] into a [SqlDataSet]. Port of `get_output_data_set`. Caches
     * results so multi-parent DAGs convert each node exactly once. Note: as in Python, the
     * cached value is **not** copied on re-fetch yet — the W14 wiring should match the Python
     * `with_copied_sql_node()` semantics once the `copyNode()` story is fully exercised by
     * corpus tests.
     */
    fun getOutputDataSet(dataflowPlanNode: DataflowPlanNode): SqlDataSet {
        val cached = nodeToOutputDataSet[dataflowPlanNode]
        if (cached != null) return cached
        val result = dataflowPlanNode.accept(this)
        nodeToOutputDataSet[dataflowPlanNode] = result
        return result
    }

    fun cacheOutputDataSets(dataflowPlanNodes: List<DataflowPlanNode>) {
        for (node in dataflowPlanNodes) {
            getOutputDataSet(node)
        }
    }

    private fun nextUniqueTableAlias(): String =
        SequentialIdGenerator.createNextId(StaticIdPrefix.SUB_QUERY).strValue

    // ---------------------------------------------------------------------------------------
    // Simple leaf / projection nodes
    // ---------------------------------------------------------------------------------------

    /** Port of `visit_source_node`. */
    override fun visitSourceNode(node: ReadSqlSourceNode): SqlDataSet {
        // The ReadSqlSourceNode.dataSet is the W9a marker interface; the W12 builder seeds the
        // real concrete `SqlDataSet`. We cast and emit a fresh copy of the SELECT to satisfy
        // the SqlColumnPrunerOptimizer assumption that every dataflow node owns its own SELECT.
        val concrete = node.dataSet as SqlDataSet
        val freshSelect = concrete.checkedSqlSelectNode.copyNode()
        return SqlDataSet(instanceSet = concrete.instanceSet, sqlSelectNode = freshSelect)
    }

    /** Port of `visit_write_to_result_data_table_node`. */
    override fun visitWriteToResultDataTableNode(node: WriteToResultDataTableNode): SqlDataSet {
        val inputDataSet = getOutputDataSet(node.parentNode)
        val inputAlias = nextUniqueTableAlias()
        val createResult = CreateSelectColumnsForInstances(
            tableAlias = inputAlias,
            columnResolver = columnAssociationResolver,
        ).transform(inputDataSet.instanceSet)
        return SqlDataSet(
            instanceSet = inputDataSet.instanceSet,
            sqlSelectNode = makeSelect(
                description = node.description,
                selectColumns = createResult.getColumns(outputColumnOrderer),
                fromSource = inputDataSet.checkedSqlSelectNode,
                fromSourceAlias = inputAlias,
            ),
        )
    }

    /** Port of `visit_write_to_result_table_node`. */
    override fun visitWriteToResultTableNode(node: WriteToResultTableNode): SqlDataSet {
        val inputDataSet = getOutputDataSet(node.parentNode)
        val inputAlias = nextUniqueTableAlias()
        val createResult = CreateSelectColumnsForInstances(
            tableAlias = inputAlias,
            columnResolver = columnAssociationResolver,
        ).transform(inputDataSet.instanceSet)
        return SqlDataSet(
            instanceSet = inputDataSet.instanceSet,
            sqlNode = SqlCreateTableAsNode.create(
                sqlTable = node.outputSqlTable,
                parentNode = makeSelect(
                    description = node.description,
                    selectColumns = createResult.getColumns(outputColumnOrderer),
                    fromSource = inputDataSet.checkedSqlSelectNode,
                    fromSourceAlias = inputAlias,
                ),
            ),
        )
    }

    /** Port of `visit_selector_node`. */
    override fun visitSelectorNode(node: SelectorNode): SqlDataSet {
        val fromDataSet = getOutputDataSet(node.parentNode)
        var outputInstanceSet = fromDataSet.instanceSet.transform(
            SelectElementsTransform(includeSpecs = node.includeSpecs, excludeSpecs = null),
        )
        val fromAlias = nextUniqueTableAlias()
        outputInstanceSet = outputInstanceSet.transform(
            ChangeAssociatedColumns(columnAssociationResolver),
        )
        val selectColumns = CreateSelectColumnsForInstances(fromAlias, columnAssociationResolver)
            .transform(outputInstanceSet)
            .getColumns(null)
        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = selectColumns,
                fromSource = fromDataSet.checkedSqlSelectNode,
                fromSourceAlias = fromAlias,
                cteSources = emptyList(),
                joinDescs = emptyList(),
                groupBys = if (node.distinct) selectColumns else emptyList(),
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )
    }

    /** Port of `visit_order_by_limit_node`. */
    override fun visitOrderByLimitNode(node: OrderByLimitNode): SqlDataSet {
        val fromDataSet = getOutputDataSet(node.parentNode)
        val fromAlias = nextUniqueTableAlias()
        val outputInstanceSet = fromDataSet.instanceSet.transform(
            ChangeAssociatedColumns(columnAssociationResolver),
        )
        val orderByDescriptions = node.orderBySpecs.map { spec ->
            SqlOrderByDescription(
                expr = SqlColumnReferenceExpression.create(
                    colRef = SqlColumnReference(
                        tableAlias = fromAlias,
                        columnName = columnAssociationResolver.resolveSpec(spec.instanceSpec).columnName,
                    ),
                    shouldRenderTableAlias = true,
                ),
                desc = spec.descending,
            )
        }
        val selectColumns = CreateSelectColumnsForInstances(fromAlias, columnAssociationResolver)
            .transform(outputInstanceSet).getColumns(null)
        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = selectColumns,
                fromSource = fromDataSet.checkedSqlSelectNode,
                fromSourceAlias = fromAlias,
                cteSources = emptyList(),
                joinDescs = emptyList(),
                groupBys = emptyList(),
                orderBys = orderByDescriptions,
                where = null,
                limit = node.limit,
                distinct = false,
            ),
        )
    }

    /** Port of `visit_constrain_time_range_node`. */
    override fun visitConstrainTimeRangeNode(node: ConstrainTimeRangeNode): SqlDataSet {
        val fromDataSet = getOutputDataSet(node.parentNode)
        val fromAlias = nextUniqueTableAlias()
        val metricTimeInstance = fromDataSet.metricTimeInstanceForTimeConstraint
        val whereCond = makeTimeRangeComparisonExpr(
            tableAlias = fromAlias,
            columnAlias = metricTimeInstance.associatedColumn.columnName,
            timeRangeConstraint = node.timeRangeConstraint,
        )
        val outputInstanceSet = fromDataSet.instanceSet.transform(
            ChangeAssociatedColumns(columnAssociationResolver),
        )
        val selectColumns = CreateSelectColumnsForInstances(fromAlias, columnAssociationResolver)
            .transform(outputInstanceSet).getColumns(null)
        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = selectColumns,
                fromSource = fromDataSet.checkedSqlSelectNode,
                fromSourceAlias = fromAlias,
                cteSources = emptyList(),
                joinDescs = emptyList(),
                groupBys = emptyList(),
                orderBys = emptyList(),
                where = whereCond,
                limit = null,
                distinct = false,
            ),
        )
    }

    /** Port of `visit_add_generated_uuid_column_node`. */
    override fun visitAddGeneratedUuidColumnNode(node: AddGeneratedUuidColumnNode): SqlDataSet {
        val inputDataSet = getOutputDataSet(node.parentNode)
        val inputAlias = nextUniqueTableAlias()
        val genUuidSpec = MetadataSpec(elementName = "mf_internal_uuid", aggType = null)
        val outputColumnAssociation = columnAssociationResolver.resolveSpec(genUuidSpec)
        val outputInstanceSet = inputDataSet.instanceSet.transform(
            AddMetadata(
                listOf(
                    MetadataInstance(
                        associatedColumns = listOf(outputColumnAssociation),
                        spec = genUuidSpec,
                    ),
                ),
            ),
        )
        val uuidColumn = SqlSelectColumn(
            expr = SqlGenerateUuidExpression.create(),
            columnAlias = outputColumnAssociation.columnName,
        )
        val passthroughColumns = CreateSelectColumnsForInstances(inputAlias, columnAssociationResolver)
            .transform(inputDataSet.instanceSet)
            .getColumns(null)
        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = makeSelect(
                description = "Add column with generated UUID",
                selectColumns = passthroughColumns + uuidColumn,
                fromSource = inputDataSet.checkedSqlSelectNode,
                fromSourceAlias = inputAlias,
            ),
        )
    }

    /** Port of `visit_min_max_node`. */
    override fun visitMinMaxNode(node: MinMaxNode): SqlDataSet {
        val parentDataSet = getOutputDataSet(node.parentNode)
        val parentAlias = nextUniqueTableAlias()
        check(parentDataSet.checkedSqlSelectNode.selectColumns.size == 1) {
            "MinMaxNode supports exactly one parent select column."
        }
        val parentColumnAlias = parentDataSet.checkedSqlSelectNode.selectColumns[0].columnAlias
        val selectColumns = mutableListOf<SqlSelectColumn>()
        val metadataInstances = mutableListOf<MetadataInstance>()
        for (aggType in listOf(AggregationType.MIN, AggregationType.MAX)) {
            val metadataSpec = MetadataSpec(elementName = parentColumnAlias, aggType = aggType)
            val outputAssociation = columnAssociationResolver.resolveSpec(metadataSpec)
            selectColumns += SqlSelectColumn(
                expr = SqlFunctionExpression.buildExpressionFromAggregationType(
                    aggregationType = aggType,
                    sqlColumnExpression = SqlColumnReferenceExpression.create(
                        colRef = SqlColumnReference(tableAlias = parentAlias, columnName = parentColumnAlias),
                        shouldRenderTableAlias = true,
                    ),
                    aggParams = null,
                ),
                columnAlias = outputAssociation.columnName,
            )
            metadataInstances += MetadataInstance(
                associatedColumns = listOf(outputAssociation),
                spec = metadataSpec,
            )
        }
        return SqlDataSet(
            instanceSet = parentDataSet.instanceSet.transform(ConvertToMetadata(metadataInstances)),
            sqlSelectNode = makeSelect(
                description = node.description,
                selectColumns = selectColumns,
                fromSource = parentDataSet.checkedSqlSelectNode,
                fromSourceAlias = parentAlias,
            ),
        )
    }

    /** Port of `visit_alias_specs_node`. */
    override fun visitAliasSpecsNode(node: AliasSpecsNode): SqlDataSet {
        val parentDataSet = getOutputDataSet(node.parentNode)
        val parentAlias = nextUniqueTableAlias()

        val inputToOutputs = LinkedHashMap<InstanceSpec, MutableList<InstanceSpec>>()
        for (change in node.changeSpecs) {
            inputToOutputs.getOrPut(change.inputSpec) { mutableListOf() } += change.outputSpec
        }

        val outputInstances = mutableListOf<MdoInstance>()
        val outputSelectColumns = mutableListOf<SqlSelectColumn>()
        for (parentInstance in parentDataSet.instanceSet.asList) {
            val parentSpec = parentInstance.spec.withoutFilterSpecs()
            val newSpecs = inputToOutputs[parentSpec]
            if (newSpecs != null) {
                for (newSpec in newSpecs) {
                    val newInstance = aliasInstance(parentInstance, newSpec)
                    outputInstances += newInstance
                    outputSelectColumns += SqlSelectColumn(
                        expr = SqlColumnReferenceExpression.fromColumnReference(
                            tableAlias = parentAlias,
                            columnName = parentInstance.associatedColumn.columnName,
                        ),
                        columnAlias = newInstance.associatedColumn.columnName,
                    )
                }
            } else {
                outputInstances += parentInstance
                val columnName = parentInstance.associatedColumn.columnName
                outputSelectColumns += SqlSelectColumn(
                    expr = SqlColumnReferenceExpression.fromColumnReference(
                        tableAlias = parentAlias,
                        columnName = columnName,
                    ),
                    columnAlias = columnName,
                )
            }
        }

        return SqlDataSet(
            instanceSet = InstanceSet.groupInstancesByType(outputInstances),
            sqlSelectNode = makeSelect(
                description = node.description,
                selectColumns = outputSelectColumns,
                fromSource = parentDataSet.checkedSqlSelectNode,
                fromSourceAlias = parentAlias,
            ),
        )
    }

    /** Port of `visit_metric_time_dimension_transform_node`. */
    override fun visitMetricTimeDimensionTransformNode(node: MetricTimeDimensionTransformNode): SqlDataSet {
        val inputDataSet = getOutputDataSet(node.parentNode)

        // Match Python: filter simple metric inputs whose agg_time_dimension equals the node's
        // aggregation_time_dimension_reference. The Python uses manifest_object_lookup to find
        // the SimpleMetricInput by element name; we use the lookup as well.
        val manifestObjectLookup = semanticManifestLookup.let { lookup ->
            // ManifestObjectLookup is constructed lazily in W7c — but the W12 builder threads
            // it via the converter only, not the lookup. We re-build a transient instance here
            // mirroring the SemanticManifestLookup wiring. If this becomes a bottleneck, W14
            // can move ManifestObjectLookup onto SemanticManifestLookup.
            cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup(lookup.semanticManifest)
        }

        val outputSimpleMetricInputInstances = mutableListOf<SimpleMetricInputInstance>()
        for (instance in inputDataSet.instanceSet.simpleMetricInputInstances) {
            val simpleMetricInput = manifestObjectLookup.simpleMetricNameToInput[instance.spec.elementName]
                ?: continue
            val aggTimeDim = simpleMetricInput.aggTimeDimensionName
            if (aggTimeDim == node.aggregationTimeDimensionReference.elementName) {
                outputSimpleMetricInputInstances += instance
            }
        }

        val matchingTimeDimensionInstances = mutableListOf<TimeDimensionInstance>()
        for (instance in inputDataSet.instanceSet.timeDimensionInstances) {
            if (instance.spec.entityLinks.isEmpty() &&
                instance.spec.reference == node.aggregationTimeDimensionReference
            ) {
                matchingTimeDimensionInstances += instance
            }
        }

        val outputTimeDimensionInstances = inputDataSet.instanceSet.timeDimensionInstances.toMutableList()
        val outputColumnToInputColumn = LinkedHashMap<String, String>()
        for (matching in matchingTimeDimensionInstances) {
            val metricTimeSpec =
                cc.monomer.metricflow.domain.dataflow.dataset.DataSet.metricTimeDimensionSpec(
                    matching.spec.timeGranularity ?: continue,
                ).let { base ->
                    if (matching.spec.datePart != null) {
                        cc.monomer.metricflow.domain.dataflow.dataset.DataSet
                        .metricTimeDimensionSpec(checkNotNull(matching.spec.datePart))
                    } else {
                        base
                    }
                }
            val metricTimeColumnAssociation = columnAssociationResolver.resolveSpec(metricTimeSpec)
            outputTimeDimensionInstances += TimeDimensionInstance(
                associatedColumns = listOf(metricTimeColumnAssociation),
                spec = metricTimeSpec,
                definedFrom = matching.definedFrom,
            )
            outputColumnToInputColumn[metricTimeColumnAssociation.columnName] =
                matching.associatedColumn.columnName
        }

        var outputInstanceSet = InstanceSet(
            simpleMetricInputInstances = outputSimpleMetricInputInstances,
            dimensionInstances = inputDataSet.instanceSet.dimensionInstances,
            timeDimensionInstances = outputTimeDimensionInstances,
            entityInstances = inputDataSet.instanceSet.entityInstances,
            metricInstances = inputDataSet.instanceSet.metricInstances,
            groupByMetricInstances = inputDataSet.instanceSet.groupByMetricInstances,
            metadataInstances = inputDataSet.instanceSet.metadataInstances,
        )
        outputInstanceSet = ChangeAssociatedColumns(columnAssociationResolver).transform(outputInstanceSet)

        val fromAlias = nextUniqueTableAlias()
        val selectColumns = CreateSelectColumnsForInstances(
            tableAlias = fromAlias,
            columnResolver = columnAssociationResolver,
            outputToInputColumnMapping = outputColumnToInputColumn,
        ).transform(outputInstanceSet).getColumns(null)

        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = makeSelect(
                description = node.description,
                selectColumns = selectColumns,
                fromSource = inputDataSet.checkedSqlSelectNode,
                fromSourceAlias = fromAlias,
            ),
        )
    }

    /** Port of `visit_aggregate_simple_metric_inputs_node`. */
    override fun visitAggregateSimpleMetricInputsNode(node: AggregateSimpleMetricInputsNode): SqlDataSet {
        val fromDataSet = getOutputDataSet(node.parentNode)
        var aggregatedInstanceSet = fromDataSet.instanceSet.transform(
            ChangeSimpleMetricInputAggregationState(
                mapOf(
                    AggregationState.NON_AGGREGATED to AggregationState.COMPLETE,
                    AggregationState.COMPLETE to AggregationState.COMPLETE,
                    AggregationState.PARTIAL to AggregationState.COMPLETE,
                ),
            ),
        )
        aggregatedInstanceSet = aggregatedInstanceSet.transform(
            ChangeAssociatedColumns(columnAssociationResolver),
        )
        aggregatedInstanceSet = aggregatedInstanceSet.transform(
            UpdateSimpleMetricInputFillNullsWith(node.nullFillValueMapping),
        )

        val fromAlias = nextUniqueTableAlias()
        val manifestObjectLookup = cc.monomer.metricflow.domain.semantic_graph
            .ManifestObjectLookup(semanticManifestLookup.semanticManifest)

        val createResult = aggregatedInstanceSet.transform(
            CreateAggregatedSimpleMetricInputsTransform(
                tableAlias = fromAlias,
                columnResolver = columnAssociationResolver,
                manifestObjectLookup = manifestObjectLookup,
            ),
        )

        return SqlDataSet(
            instanceSet = aggregatedInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = createResult.selectColumnSet.columnsInDefaultOrder,
                fromSource = fromDataSet.checkedSqlSelectNode,
                fromSourceAlias = fromAlias,
                cteSources = emptyList(),
                joinDescs = emptyList(),
                groupBys = createResult.groupByColumnSet.columnsInDefaultOrder,
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Compute metrics + combine outputs
    // ---------------------------------------------------------------------------------------

    /** Port of `visit_compute_metrics_node`. */
    override fun visitComputeMetricsNode(node: ComputeMetricsNode): SqlDataSet {
        val fromDataSet = getOutputDataSet(node.parentNode)
        val fromAlias = nextUniqueTableAlias()

        var outputInstanceSet = fromDataSet.instanceSet.transform(RemoveSimpleMetricInputTransform())
        outputInstanceSet = outputInstanceSet.transform(ChangeAssociatedColumns(columnAssociationResolver))
        outputInstanceSet = outputInstanceSet.transform(RemoveMetrics(node.passthroughMetricSpecs))

        if (node.outputGroupByMetricInstances) {
            check(
                node.metricSpecs.size == 1 && outputInstanceSet.entityInstances.size == 1,
            ) {
                "Group by metrics currently only support exactly one metric grouped by exactly one entity."
            }
        }

        val nonMetricSelectColumnSet: SelectColumnSet = CreateSelectColumnsForInstances(
            tableAlias = fromAlias,
            columnResolver = columnAssociationResolver,
        ).transform(outputInstanceSet).selectColumnSet

        val metricSelectColumns = mutableListOf<SqlSelectColumn>()
        val metricInstances = mutableListOf<MetricInstance>()
        var groupByMetricInstance: GroupByMetricInstance? = null

        val simpleMetricInputElementNameToInstance: Map<String, SimpleMetricInputInstance> =
            fromDataSet.instanceSet.simpleMetricInputInstances.associateBy { it.spec.elementName }

        for (metricSpec in node.computedMetricSpecs) {
            val metric = metricLookup.getMetric(
                cc.monomer.metricflow.domain.manifest.model.references.MetricReference(
                    elementName = metricSpec.elementName,
                ),
            )
            val metricExpr: SqlExpressionNode = when (metric.type) {
                MetricType.RATIO -> {
                    val numerator = checkNotNull(metric.typeParams.numerator) {
                        "Missing numerator for ratio metric, should have been caught in validation."
                    }
                    val denominator = checkNotNull(metric.typeParams.denominator) {
                        "Missing denominator for ratio metric, should have been caught in validation."
                    }
                    val numeratorColumn = columnAssociationResolver.resolveSpec(
                        MetricSpec.fromReference(numerator.postAggregationReference),
                    ).columnName
                    val denominatorColumn = columnAssociationResolver.resolveSpec(
                        MetricSpec.fromReference(denominator.postAggregationReference),
                    ).columnName
                    SqlRatioComputationExpression.create(
                        numerator = SqlColumnReferenceExpression.create(
                            colRef = SqlColumnReference(tableAlias = fromAlias, columnName = numeratorColumn),
                            shouldRenderTableAlias = true,
                        ),
                        denominator = SqlColumnReferenceExpression.create(
                            colRef = SqlColumnReference(tableAlias = fromAlias, columnName = denominatorColumn),
                            shouldRenderTableAlias = true,
                        ),
                    )
                }
                MetricType.SIMPLE -> {
                    val instance = checkNotNull(simpleMetricInputElementNameToInstance[metricSpec.elementName]) {
                        "Expected a simple metric instance with element name matching the metric " +
                            "name to be present in the input. metric=${metricSpec.elementName}"
                    }
                    makeColRefOrCoalesceExpr(
                        columnName = instance.associatedColumn.columnName,
                        nullFillValue = metric.typeParams.fillNullsWith,
                        fromAlias = fromAlias,
                    )
                }
                MetricType.CUMULATIVE -> {
                    val cumulativeTypeParams = checkNotNull(metric.typeParams.cumulativeTypeParams) {
                        "A cumulative metric should have `cumulativeTypeParams` set: metric=$metric"
                    }
                    val cumulativeMetric = checkNotNull(cumulativeTypeParams.metric) {
                        "A cumulative metric should have `cumulativeTypeParams.metric` set: metric=$metric"
                    }
                    makeColRefOrCoalesceExpr(
                        columnName = cumulativeMetric.name,
                        nullFillValue = metric.typeParams.fillNullsWith,
                        fromAlias = fromAlias,
                    )
                }
                MetricType.DERIVED -> {
                    val expr = checkNotNull(metric.typeParams.expr) {
                        "Derived metrics are required to have an `expr` in their YAML definition."
                    }
                    SqlStringExpression.create(
                        sqlExpr = expr,
                        bindParameterSet =
                            cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet.EMPTY,
                        requiresParenthesis = true,
                        usedColumns = null,
                    )
                }
                MetricType.CONVERSION -> {
                    val conversionTypeParams = checkNotNull(metric.typeParams.conversionTypeParams) {
                        "A conversion metric should have typeParams.conversionTypeParams defined."
                    }
                    val baseInputMetric = checkNotNull(conversionTypeParams.baseMetric) {
                        "A conversion metric must have a base metric."
                    }
                    val convInputMetric = checkNotNull(conversionTypeParams.conversionMetric) {
                        "A conversion metric must have a conversion metric."
                    }
                    val baseInstance = checkNotNull(simpleMetricInputElementNameToInstance[baseInputMetric.name]) {
                        "Expected base input instance: ${baseInputMetric.name}"
                    }
                    val convInstance = checkNotNull(simpleMetricInputElementNameToInstance[convInputMetric.name]) {
                        "Expected conversion input instance: ${convInputMetric.name}"
                    }
                    val baseRef = SqlColumnReferenceExpression.create(
                        colRef = SqlColumnReference(
                            tableAlias = fromAlias,
                            columnName = baseInstance.associatedColumn.columnName,
                        ),
                        shouldRenderTableAlias = true,
                    )
                    val convRef = SqlColumnReferenceExpression.create(
                        colRef = SqlColumnReference(
                            tableAlias = fromAlias,
                            columnName = convInstance.associatedColumn.columnName,
                        ),
                        shouldRenderTableAlias = true,
                    )
                    when (conversionTypeParams.calculation) {
                        ConversionCalculationType.CONVERSION_RATE ->
                            SqlRatioComputationExpression.create(numerator = convRef, denominator = baseRef)
                        ConversionCalculationType.CONVERSIONS -> convRef
                    }
                }
            }

            val definedFrom = MetricModelReference(metricName = metricSpec.elementName)
            val outputColumnAssociation: cc.monomer.metricflow.domain.spec.ColumnAssociation
            if (node.outputGroupByMetricInstances) {
                val entitySpec = outputInstanceSet.entityInstances[0].spec
                val groupBySpec = GroupByMetricSpec(
                    elementName = metricSpec.elementName,
                    entityLinks = emptyList(),
                    metricSubqueryEntityLinks = entitySpec.entityLinks + entitySpec.reference,
                    alias = null,
                )
                outputColumnAssociation = columnAssociationResolver.resolveSpec(groupBySpec)
                groupByMetricInstance = GroupByMetricInstance(
                    associatedColumns = listOf(outputColumnAssociation),
                    definedFrom = definedFrom,
                    spec = groupBySpec,
                )
            } else {
                outputColumnAssociation = columnAssociationResolver.resolveSpec(metricSpec)
                metricInstances += MetricInstance(
                    associatedColumns = listOf(outputColumnAssociation),
                    definedFrom = definedFrom,
                    spec = metricSpec,
                )
            }
            metricSelectColumns += SqlSelectColumn(
                expr = metricExpr,
                columnAlias = outputColumnAssociation.columnName,
            )
        }

        val capturedGroupBy = groupByMetricInstance
        outputInstanceSet = if (capturedGroupBy != null) {
            outputInstanceSet.transform(AddGroupByMetric(capturedGroupBy))
        } else {
            outputInstanceSet.transform(AddMetrics(metricInstances))
        }

        val combinedSelectColumnSet = nonMetricSelectColumnSet.merge(
            SelectColumnSet.create(
                metricColumns = metricSelectColumns,
                simpleMetricInputColumns = emptyList(),
                dimensionColumns = emptyList(),
                timeDimensionColumns = emptyList(),
                entityColumns = emptyList(),
                groupByMetricColumns = emptyList(),
                metadataColumns = emptyList(),
            ),
        )

        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = makeSelect(
                description = node.description,
                selectColumns = combinedSelectColumnSet.columnsInDefaultOrder,
                fromSource = fromDataSet.checkedSqlSelectNode,
                fromSourceAlias = fromAlias,
            ),
        )
    }

    /** Port of `visit_combine_aggregated_outputs_node`. */
    override fun visitCombineAggregatedOutputsNode(node: CombineAggregatedOutputsNode): SqlDataSet {
        check(node.parentNodes.size > 1) {
            "Shouldn't have a CombineAggregatedOutputsNode in the dataflow plan if there's only 1 parent."
        }
        val parentDataSets = mutableListOf<AnnotatedSqlDataSet>()
        val tableAliasToInstanceSet = LinkedHashMap<String, InstanceSet>()
        for (parentNode in node.parentNodes) {
            val parentSqlDataSet = getOutputDataSet(parentNode)
            val tableAlias = nextUniqueTableAlias()
            parentDataSets += AnnotatedSqlDataSet(dataSet = parentSqlDataSet, alias = tableAlias)
            tableAliasToInstanceSet[tableAlias] = parentSqlDataSet.instanceSet
        }
        val fromDataSet = parentDataSets[0]
        val joinDataSets = parentDataSets.drop(1)

        val linkableSpecs = fromDataSet.dataSet.instanceSet.specSet.linkableSpecs
        check(joinDataSets.all { it.dataSet.instanceSet.specSet.linkableSpecs.toSet() == linkableSpecs.toSet() }) {
            "All join data sets should have the same set of linkable instances as the `from` dataset " +
                "since all values are coalesced."
        }

        val linkableSpecSet = SelectOnlyLinkableSpecs().transform(fromDataSet.dataSet.instanceSet.specSet)
        val joinType = if (linkableSpecSet.allSpecs.isEmpty()) SqlJoinType.CROSS_JOIN else SqlJoinType.FULL_OUTER

        val joinDescriptions = mutableListOf<SqlJoinDescription>()
        val columnAssociations = linkableSpecSet.allSpecs.map { columnAssociationResolver.resolveSpec(it) }
        val columnNames = columnAssociations.map { it.columnName }
        val aliasesSeen = mutableListOf(fromDataSet.alias)
        for (joinDataSet in joinDataSets) {
            joinDescriptions += SqlPlanJoinBuilder.makeJoinDescriptionForCombiningDatasets(
                fromDataSet = fromDataSet,
                joinDataSet = joinDataSet,
                joinType = joinType,
                columnNames = columnNames,
                tableAliasesForCoalesce = aliasesSeen.toList(),
            )
            aliasesSeen += joinDataSet.alias
        }

        var outputInstanceSet = InstanceSet.merge(parentDataSets.map { it.dataSet.instanceSet })
        outputInstanceSet = outputInstanceSet.transform(ChangeAssociatedColumns(columnAssociationResolver))

        var aggregatedSelectColumns = SelectColumnSet.EMPTY
        for ((tableAlias, instanceSet) in tableAliasToInstanceSet) {
            aggregatedSelectColumns = aggregatedSelectColumns.merge(
                CreateSelectColumnForCombineOutputNode(
                    tableAlias = tableAlias,
                    columnResolver = columnAssociationResolver,
                    metricLookup = metricLookup,
                ).transform(instanceSet),
            )
        }
        val linkableSelectColumnSet = CreateSelectCoalescedColumnsForLinkableSpecs(
            columnAssociationResolver = columnAssociationResolver,
            tableAliases = parentDataSets.map { it.alias },
        ).transform(linkableSpecSet)
        val combinedSelectColumnSet = linkableSelectColumnSet.merge(aggregatedSelectColumns)

        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = combinedSelectColumnSet.columnsInDefaultOrder,
                fromSource = fromDataSet.dataSet.checkedSqlSelectNode,
                fromSourceAlias = fromDataSet.alias,
                cteSources = emptyList(),
                joinDescs = joinDescriptions,
                groupBys = linkableSelectColumnSet.columnsInDefaultOrder,
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Joins
    // ---------------------------------------------------------------------------------------

    /** Port of `visit_join_on_entities_node`. */
    override fun visitJoinOnEntitiesNode(node: JoinOnEntitiesNode): SqlDataSet {
        val fromDataSet = getOutputDataSet(node.leftNode)
        val fromAlias = nextUniqueTableAlias()

        val fromDataSetOutputInstanceSet = fromDataSet.instanceSet.transform(
            SelectElementsTransform(
                includeSpecs = fromDataSet.instanceSet.specSet,
                excludeSpecs = null,
            ),
        ).transform(
            ChangeSimpleMetricInputAggregationState(
                mapOf(
                    AggregationState.NON_AGGREGATED to AggregationState.NON_AGGREGATED,
                    AggregationState.COMPLETE to AggregationState.PARTIAL,
                    AggregationState.PARTIAL to AggregationState.PARTIAL,
                ),
            ),
        )
        val instancesToBuildSimpleSelectColumnsFor = LinkedHashMap<String, InstanceSet>()
        instancesToBuildSimpleSelectColumnsFor[fromAlias] = fromDataSetOutputInstanceSet

        var outputInstanceSet = fromDataSetOutputInstanceSet
        val selectColumns = mutableListOf<SqlSelectColumn>()
        val sqlJoinDescs = mutableListOf<SqlJoinDescription>()

        for (joinDescription in node.joinTargets) {
            val joinOnEntity = joinDescription.joinOnEntity
            val rightNodeToJoin = joinDescription.joinNode
            val rightDataSet = getOutputDataSet(rightNodeToJoin)
            val rightDataSetAlias = nextUniqueTableAlias()

            val sqlJoinDesc = SqlPlanJoinBuilder.makeBaseOutputJoinDescription(
                leftDataSet = AnnotatedSqlDataSet(dataSet = fromDataSet, alias = fromAlias),
                rightDataSet = AnnotatedSqlDataSet(dataSet = rightDataSet, alias = rightDataSetAlias),
                joinDescription = joinDescription,
            )
            sqlJoinDescs += sqlJoinDesc

            val rightInstanceSetAfterJoin: InstanceSet
            if (joinOnEntity != null) {
                val rightInstanceSetFiltered = FilterLinkableInstancesWithLeadingLink(joinOnEntity)
                    .transform(rightDataSet.instanceSet)
                val newInstances = mutableListOf<MdoInstance>()
                for (original in rightInstanceSetFiltered.linkableInstances) {
                    val newInstance = original.withEntityPrefix(
                        joinOnEntity, columnAssociationResolver,
                    )
                    val originalColumnName = original.associatedColumn.columnName
                    val newColumnName = newInstance.associatedColumn.columnName
                    selectColumns += SqlSelectColumn(
                        expr = SqlColumnReferenceExpression.fromColumnReference(
                            tableAlias = rightDataSetAlias,
                            columnName = originalColumnName,
                        ),
                        columnAlias = newColumnName,
                    )
                    newInstances += newInstance
                }
                rightInstanceSetAfterJoin = InstanceSet.groupInstancesByType(newInstances)
            } else {
                rightInstanceSetAfterJoin = rightDataSet.instanceSet
                instancesToBuildSimpleSelectColumnsFor[rightDataSetAlias] = rightInstanceSetAfterJoin
            }
            outputInstanceSet = InstanceSet.merge(listOf(outputInstanceSet, rightInstanceSetAfterJoin))
        }

        val simpleSelectColumns = createSimpleSelectColumnsForInstanceSets(
            columnResolver = columnAssociationResolver,
            tableAliasToInstanceSet = instancesToBuildSimpleSelectColumnsFor,
        )

        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = selectColumns + simpleSelectColumns,
                fromSource = fromDataSet.checkedSqlSelectNode,
                fromSourceAlias = fromAlias,
                cteSources = emptyList(),
                joinDescs = sqlJoinDescs,
                groupBys = emptyList(),
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Where filter
    // ---------------------------------------------------------------------------------------

    /** Port of `visit_where_constraint_node`. */
    override fun visitWhereConstraintNode(node: WhereFilterNode): SqlDataSet {
        val inputDataSet = getOutputDataSet(node.parentNode)
        val modifiedResolver = columnAssociationResolver.withOptions(
            dunderPrefixSimpleMetricInputs = false,
        )

        val columnAliasRenamer = ColumnAliasRenamer()
        val inputAliasToIntermediate = LinkedHashMap<String, String>()
        val intermediateAliasToInstance = LinkedHashMap<String, MdoInstance>()
        for (instance in inputDataSet.instanceSet.asList) {
            val nextAlias = modifiedResolver.resolveSpec(instance.spec).columnName
            inputAliasToIntermediate[instance.associatedColumn.columnName] = nextAlias
            intermediateAliasToInstance[nextAlias] = instance
        }

        val innerQueryAlias = nextUniqueTableAlias()
        var outerSelectNode = columnAliasRenamer.renameViaSubquery(
            selectStatementNode = inputDataSet.checkedSqlSelectNode,
            previousToNext = inputAliasToIntermediate,
            description = "Constrain Output with WHERE",
            innerQueryAlias = innerQueryAlias,
        )
        outerSelectNode = outerSelectNode.withWhereClause(renderWhereConstraintExpr(node.filterSpecs))

        val intermediateToOutput = LinkedHashMap<String, String>()
        for ((interAlias, instance) in intermediateAliasToInstance) {
            intermediateToOutput[interAlias] = columnAssociationResolver.resolveSpec(instance.spec).columnName
        }
        outerSelectNode = columnAliasRenamer.rename(
            selectStatementNode = outerSelectNode,
            previousToNext = intermediateToOutput,
        )

        return SqlDataSet(
            instanceSet = inputDataSet.instanceSet.transform(
                ChangeAssociatedColumns(columnAssociationResolver),
            ),
            sqlSelectNode = outerSelectNode,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Time spine / conversion joins
    // ---------------------------------------------------------------------------------------

    /** Build the time-spine relation used by cumulative metric joins. */
    private fun makeTimeSpineDataSet(
        aggTimeDimensionInstances: List<TimeDimensionInstance>,
        timeRangeConstraint: TimeRangeConstraint?,
    ): SqlDataSet {
        check(aggTimeDimensionInstances.isNotEmpty()) {
            "A time spine data set requires at least one aggregation time dimension."
        }
        val queriedSpecs = aggTimeDimensionInstances.map { it.spec }
        val timeSpineSources = chooseTimeSpineSources(queriedSpecs)
        check(timeSpineSources.size == 1) {
            "Cumulative metrics require exactly one time spine source, got $timeSpineSources."
        }
        val timeSpineSource = timeSpineSources.single()
        val timeSpineAlias = nextUniqueTableAlias()
        val baseColumnExpr = SqlColumnReferenceExpression.fromColumnReference(
            tableAlias = timeSpineAlias,
            columnName = timeSpineSource.baseColumn,
        )

        val requiredSpecs = queriedSpecs
        val selectColumns = requiredSpecs.map { spec ->
            val columnAlias = columnAssociationResolver.resolveSpec(spec).columnName
            val expression: SqlExpressionNode = when {
                spec.datePart != null -> SqlExtractExpression.create(spec.datePart, baseColumnExpr)
                spec.timeGranularity == null -> error("Time dimension spec has no grain or date part: $spec")
                spec.timeGranularity.baseGranularity == timeSpineSource.baseGranularity -> baseColumnExpr
                spec.timeGranularity.isCustomGranularity -> {
                    val custom = timeSpineSource.customGranularities.firstOrNull {
                        it.name == spec.timeGranularity.name
                    } ?: error(
                        "Custom granularity ${spec.timeGranularity.name} is not defined by " +
                            "time spine ${timeSpineSource.sqlTable.sql}.",
                    )
                    SqlColumnReferenceExpression.fromColumnReference(
                        tableAlias = timeSpineAlias,
                        columnName = custom.parsedColumnName,
                    )
                }
                else -> SqlDateTruncExpression.create(spec.timeGranularity.baseGranularity, baseColumnExpr)
            }
            SqlSelectColumn(expr = expression, columnAlias = columnAlias)
        }

        val groupByColumns = if (requiredSpecs.all { it.timeGranularity?.baseGranularity != timeSpineSource.baseGranularity }) {
            selectColumns
        } else {
            emptyList()
        }
        val sourceSelect = SqlSelectStatementNode.create(
            description = timeSpineSource.dataSetDescription,
            selectColumns = selectColumns,
            fromSource = SqlTableNode.create(timeSpineSource.sqlTable),
            fromSourceAlias = timeSpineAlias,
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = groupByColumns,
            orderBys = emptyList(),
            where = timeRangeConstraint?.let {
                makeTimeRangeComparisonExpr(
                    tableAlias = timeSpineAlias,
                    columnAlias = timeSpineSource.baseColumn,
                    timeRangeConstraint = it,
                )
            },
            limit = null,
            distinct = false,
        )
        val sourceInstances = queriedSpecs.map { spec ->
            TimeDimensionInstance(
                definedFrom = listOf(
                    SemanticModelElementReference(
                        semanticModelName = timeSpineSource.sqlTable.tableName,
                        elementName = spec.elementName,
                    ),
                ),
                associatedColumns = listOf(columnAssociationResolver.resolveSpec(spec)),
                spec = spec,
            )
        }
        return SqlDataSet(
            instanceSet = InstanceSet(
                simpleMetricInputInstances = emptyList(),
                dimensionInstances = emptyList(),
                timeDimensionInstances = sourceInstances,
                entityInstances = emptyList(),
                groupByMetricInstances = emptyList(),
                metricInstances = emptyList(),
                metadataInstances = emptyList(),
            ),
            sqlSelectNode = sourceSelect,
        )
    }

    /** Port of `TimeSpineSource.choose_time_spine_sources`. */
    private fun chooseTimeSpineSources(
        requiredSpecs: List<cc.monomer.metricflow.domain.spec.TimeDimensionSpec>,
    ): List<TimeSpineSource> = TimeSpineSource.chooseTimeSpineSources(
        requiredTimeSpineSpecs = requiredSpecs,
        timeSpineSources = semanticManifestLookup.timeSpineSources,
    )

    override fun visitJoinOverTimeRangeNode(node: JoinOverTimeRangeNode): SqlDataSet {
        val parentDataSet = getOutputDataSet(node.parentNode)
        val parentAlias = nextUniqueTableAlias()
        val aggInstances = parentDataSet.instancesForTimeDimensions(node.queriedAggTimeDimensionSpecs)
        val timeSpineAlias = nextUniqueTableAlias()
        val timeSpineDataSet = makeTimeSpineDataSet(aggInstances, node.timeRangeConstraint)
        val joinSpec = chooseInstanceForTimeSpineJoin(aggInstances).spec
        val joinDescription = SqlPlanJoinBuilder.makeCumulativeMetricTimeRangeJoinDescription(
            node = node,
            metricDataSet = parentDataSet.annotate(parentAlias, joinSpec),
            timeSpineDataSet = timeSpineDataSet.annotate(timeSpineAlias, joinSpec),
        )
        val tableAliasToInstanceSet = linkedMapOf(
            timeSpineAlias to timeSpineDataSet.instanceSet,
            parentAlias to parentDataSet.instanceSet.transform(
                SelectElementsTransform(
                    includeSpecs = null,
                    excludeSpecs = InstanceSpecSet(
                        timeDimensionSpecs = node.queriedAggTimeDimensionSpecs,
                        metricSpecs = emptyList(),
                        simpleMetricInputSpecs = emptyList(),
                        dimensionSpecs = emptyList(),
                        entitySpecs = emptyList(),
                        groupByMetricSpecs = emptyList(),
                        metadataSpecs = emptyList(),
                    ),
                ),
            ),
        )
        return SqlDataSet(
            instanceSet = parentDataSet.instanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = createSimpleSelectColumnsForInstanceSets(
                    columnResolver = columnAssociationResolver,
                    tableAliasToInstanceSet = tableAliasToInstanceSet,
                ),
                fromSource = timeSpineDataSet.checkedSqlSelectNode,
                fromSourceAlias = timeSpineAlias,
                cteSources = emptyList(),
                joinDescs = listOf(joinDescription),
                groupBys = emptyList(),
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )
    }

    private fun chooseInstanceForTimeSpineJoin(
        aggTimeDimensionInstances: List<TimeDimensionInstance>,
    ): TimeDimensionInstance = aggTimeDimensionInstances
        .filter { it.spec.datePart == null }
        .minByOrNull { it.spec.baseGranularitySortKey }
        ?: error("No non-date-part time dimension is available for a time-spine join.")

    override fun visitJoinToTimeSpineNode(node: JoinToTimeSpineNode): SqlDataSet {
        val parentDataSet = getOutputDataSet(node.metricSourceNode)
        val parentAlias = nextUniqueTableAlias()
        val timeSpineDataSet = getOutputDataSet(node.timeSpineNode)
        val timeSpineAlias = nextUniqueTableAlias()
        if (node.timeSpineNode is OffsetCustomGranularityNode ||
            node.timeSpineNode is OffsetBaseGrainByCustomGrainNode
        ) {
            // Upstream's two time-spine selectors are removed from the SQL tree but still
            // consume their deterministic aliases after the join alias is allocated.
            nextUniqueTableAlias()
            nextUniqueTableAlias()
        }
        val requestedSpecs = node.requestedAggTimeDimensionSpecs.toSet()
        val specsFromSpine = requestedSpecs + node.joinOnTimeDimensionSpec
        val parentJoinColumnName = columnAssociationResolver.resolveSpec(node.joinOnTimeDimensionSpec).columnName
        val timeSpineJoinColumnName = timeSpineDataSet
            .instanceFromTimeDimensionGrainAndDatePart(
                node.joinOnTimeDimensionSpec.timeGranularityName,
                node.joinOnTimeDimensionSpec.datePart,
            ).associatedColumn.columnName
        val joinDescription = SqlPlanJoinBuilder.makeJoinToTimeSpineJoinDescription(
            node = node,
            timeSpineAlias = timeSpineAlias,
            timeSpineColumnName = timeSpineJoinColumnName,
            parentColumnName = parentJoinColumnName,
            parentSqlSelectNode = parentDataSet.checkedSqlSelectNode,
            parentAlias = parentAlias,
        )
        val parentOutput = parentDataSet.instanceSet.transform(
            SelectElementsTransform(
                includeSpecs = null,
                excludeSpecs = InstanceSpecSet(
                    timeDimensionSpecs = parentDataSet.instanceSet.specSet.timeDimensionSpecs.filter { it in specsFromSpine },
                    metricSpecs = emptyList(),
                    simpleMetricInputSpecs = emptyList(),
                    dimensionSpecs = emptyList(),
                    entitySpecs = emptyList(),
                    groupByMetricSpecs = emptyList(),
                    metadataSpecs = emptyList(),
                ),
            ),
        )
        val spineInstances = mutableListOf<TimeDimensionInstance>()
        val spineColumns = mutableListOf<SqlSelectColumn>()
        for (old in timeSpineDataSet.instanceSet.timeDimensionInstances) {
            val newSpec = old.spec.withWindowFunctions(emptyList())
            if (newSpec !in specsFromSpine) continue
            val newInstance = if (old.spec.windowFunctions.isNotEmpty()) {
                old.withNewSpec(newSpec, columnAssociationResolver)
            } else {
                old
            }
            spineInstances += newInstance
            spineColumns += SqlSelectColumn(
                expr = SqlColumnReferenceExpression.fromColumnReference(timeSpineAlias, old.associatedColumn.columnName),
                columnAlias = newInstance.associatedColumn.columnName,
            )
        }
        val outputInstanceSet = InstanceSet.merge(
            listOf(parentOutput, InstanceSet(
                simpleMetricInputInstances = emptyList(),
                dimensionInstances = emptyList(),
                timeDimensionInstances = spineInstances,
                entityInstances = emptyList(),
                groupByMetricInstances = emptyList(),
                metricInstances = emptyList(),
                metadataInstances = emptyList(),
            )),
        )
        val selectColumns = spineColumns + createSimpleSelectColumnsForInstanceSets(
            columnResolver = columnAssociationResolver,
            tableAliasToInstanceSet = linkedMapOf(parentAlias to parentOutput),
        )
        val where = if (node.offsetToGrain != null && node.joinOnTimeDimensionSpec !in requestedSpecs) {
            val joinExpr = SqlColumnReferenceExpression.fromColumnReference(timeSpineAlias, parentJoinColumnName)
            node.requestedAggTimeDimensionSpecs
                .map { spec ->
                    SqlComparisonExpression.create(
                        leftExpr = SqlColumnReferenceExpression.fromColumnReference(
                            timeSpineAlias,
                            columnAssociationResolver.resolveSpec(spec).columnName,
                        ),
                        comparison = SqlComparison.EQUALS,
                        rightExpr = joinExpr,
                    )
                }
                .let { conditions ->
                    when (conditions.size) {
                        0 -> null
                        1 -> conditions[0]
                        else -> SqlLogicalExpression.create(SqlLogicalOperator.OR, conditions)
                    }
                }
        } else {
            null
        }
        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = selectColumns,
                fromSource = timeSpineDataSet.checkedSqlSelectNode,
                fromSourceAlias = timeSpineAlias,
                cteSources = emptyList(),
                joinDescs = listOf(joinDescription),
                groupBys = emptyList(),
                orderBys = emptyList(),
                where = where,
                limit = null,
                distinct = false,
            ),
        )
    }

    private fun customTimeSpineSource(customGranularityName: String): TimeSpineSource =
        TimeSpineSource.buildCustomTimeSpineSources(semanticManifestLookup.timeSpineSources.values.toList())[
            customGranularityName
        ] ?: error("No time spine defines custom granularity $customGranularityName.")

    private fun customTimeSpineColumnName(customGranularityName: String): String =
        customTimeSpineSource(customGranularityName).customGranularities
            .first { it.name == customGranularityName }
            .parsedColumnName

    override fun visitJoinToCustomGranularityNode(node: JoinToCustomGranularityNode): SqlDataSet {
        val parentDataSet = getOutputDataSet(node.parentNode)
        val parentAlias = parentDataSet.checkedSqlSelectNode.fromSourceAlias
        val baseSpec = node.timeDimensionSpec.withBaseGrain()
        val parentTimeInstance = parentDataSet.instanceForTimeDimension(baseSpec)
        val parentColumn = parentDataSet.checkedSqlSelectNode.selectColumns.firstOrNull {
            it.columnAlias == parentTimeInstance.associatedColumn.columnName
        } ?: error(
            "JoinToCustomGranularityNode expected ${parentTimeInstance.associatedColumn.columnName} " +
                "in parent columns.",
        )
        val customName = node.timeDimensionSpec.timeGranularityName
            ?: error("Custom-granularity join has no granularity name.")
        val spineSource = customTimeSpineSource(customName)
        val spineAlias = nextUniqueTableAlias()
        val join = SqlJoinDescription(
            rightSource = SqlTableNode.create(spineSource.sqlTable),
            rightSourceAlias = spineAlias,
            onCondition = SqlComparisonExpression.create(
                leftExpr = parentColumn.expr,
                comparison = SqlComparison.EQUALS,
                rightExpr = SqlColumnReferenceExpression.fromColumnReference(spineAlias, spineSource.baseColumn),
            ),
            joinType = SqlJoinType.LEFT_OUTER,
        )
        val spineInstance = TimeDimensionInstance(
            definedFrom = parentTimeInstance.definedFrom,
            associatedColumns = listOf(columnAssociationResolver.resolveSpec(node.timeDimensionSpec)),
            spec = node.timeDimensionSpec,
        )
        return SqlDataSet(
            instanceSet = InstanceSet.merge(
                listOf(
                    InstanceSet(
                        simpleMetricInputInstances = emptyList(),
                        dimensionInstances = emptyList(),
                        timeDimensionInstances = listOf(spineInstance),
                        entityInstances = emptyList(),
                        groupByMetricInstances = emptyList(),
                        metricInstances = emptyList(),
                        metadataInstances = emptyList(),
                    ),
                    parentDataSet.instanceSet,
                ),
            ),
            sqlSelectNode = SqlSelectStatementNode.create(
                description = parentDataSet.checkedSqlSelectNode.description + "\n" + node.description,
                selectColumns = parentDataSet.checkedSqlSelectNode.selectColumns + SqlSelectColumn(
                    expr = SqlColumnReferenceExpression.fromColumnReference(
                        spineAlias,
                        customTimeSpineColumnName(customName),
                    ),
                    columnAlias = spineInstance.associatedColumn.columnName,
                ),
                fromSource = parentDataSet.checkedSqlSelectNode.fromSource,
                fromSourceAlias = parentAlias,
                cteSources = parentDataSet.checkedSqlSelectNode.cteSources,
                joinDescs = parentDataSet.checkedSqlSelectNode.joinDescs + join,
                groupBys = parentDataSet.checkedSqlSelectNode.groupBys,
                orderBys = parentDataSet.checkedSqlSelectNode.orderBys,
                where = parentDataSet.checkedSqlSelectNode.where,
                limit = parentDataSet.checkedSqlSelectNode.limit,
                distinct = parentDataSet.checkedSqlSelectNode.distinct,
            ),
        )
    }

    override fun visitJoinConversionEventsNode(node: JoinConversionEventsNode): SqlDataSet {
        val baseDataSet = getOutputDataSet(node.baseNode)
        val baseAlias = nextUniqueTableAlias()
        val conversionDataSet = getOutputDataSet(node.conversionNode)
        val conversionAlias = nextUniqueTableAlias()
        val baseTimeColumn = columnAssociationResolver.resolveSpec(node.baseTimeDimensionSpec).columnName
        val conversionTimeColumn = columnAssociationResolver.resolveSpec(node.conversionTimeDimensionSpec).columnName
        val entityColumn = columnAssociationResolver.resolveSpec(node.entitySpec).columnName
        val constantColumns = node.constantProperties.orEmpty().map {
            columnAssociationResolver.resolveSpec(it.baseSpec).columnName to
                columnAssociationResolver.resolveSpec(it.conversionSpec).columnName
        }
        val join = SqlPlanJoinBuilder.makeJoinConversionJoinDescription(
            node = node,
            baseDataSet = baseDataSet.annotate(baseAlias, node.baseTimeDimensionSpec),
            conversionDataSet = conversionDataSet.annotate(conversionAlias, node.conversionTimeDimensionSpec),
            columnEqualityDescriptions = listOf(
                cc.monomer.metricflow.domain.plan_conversion.helpers.ColumnEqualityDescription(
                    entityColumn,
                    entityColumn,
                    false,
                ),
            ) + constantColumns.map { (base, conversion) ->
                cc.monomer.metricflow.domain.plan_conversion.helpers.ColumnEqualityDescription(base, conversion, false)
            },
        )
        val baseRefs = baseDataSet.instanceSet.asList.map { instance ->
            instance.associatedColumn.columnName to SqlColumnReferenceExpression.fromColumnReference(
                baseAlias,
                instance.associatedColumn.columnName,
            )
        }
        val uniqueConversionNames = node.uniqueIdentifierKeys.map {
            columnAssociationResolver.resolveSpec(it).columnName
        }
        val partitionColumns = listOf(entityColumn, conversionTimeColumn) + uniqueConversionNames +
            constantColumns.map { it.second }
        val baseWindowColumns = baseRefs.map { (columnName, reference) ->
            SqlSelectColumn(
                expr = SqlWindowFunctionExpression.create(
                    sqlFunction = SqlWindowFunction.FIRST_VALUE,
                    sqlFunctionArgs = listOf(reference),
                    partitionByArgs = partitionColumns.map {
                        SqlColumnReferenceExpression.fromColumnReference(conversionAlias, it)
                    },
                    orderByArgs = listOf(
                        SqlWindowOrderByArgument(
                            expr = SqlColumnReferenceExpression.fromColumnReference(baseAlias, baseTimeColumn),
                            descending = true,
                            nullsLast = null,
                        ),
                    ),
                ),
                columnAlias = columnName,
            )
        }
        val conversionOutput = conversionDataSet.instanceSet.transform(
            SelectElementsTransform(
                includeSpecs = InstanceSpecSet(
                    simpleMetricInputSpecs = listOf(node.conversionInputMetricSpec),
                    dimensionSpecs = emptyList(),
                    timeDimensionSpecs = emptyList(),
                    entitySpecs = emptyList(),
                    metricSpecs = emptyList(),
                    groupByMetricSpecs = emptyList(),
                    metadataSpecs = emptyList(),
                ),
                excludeSpecs = null,
            ),
        )
        val uniqueColumns = uniqueConversionNames.map { name ->
            SqlSelectColumn(
                expr = SqlColumnReferenceExpression.fromColumnReference(conversionAlias, name),
                columnAlias = name,
            )
        }
        val conversionColumns = CreateSelectColumnsForInstances(conversionAlias, columnAssociationResolver)
            .transform(conversionOutput).getColumns(null)
        val deduped = SqlSelectStatementNode.create(
            description = "Dedupe the fanout with ${node.uniqueIdentifierKeys.joinToString(",") { it.dunderName }} in the conversion data set",
            selectColumns = baseWindowColumns + uniqueColumns + conversionColumns,
            fromSource = baseDataSet.checkedSqlSelectNode,
            fromSourceAlias = baseAlias,
            cteSources = emptyList(),
            joinDescs = listOf(join),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = true,
        )
        val outputAlias = nextUniqueTableAlias()
        val outputInstanceSet = ChangeAssociatedColumns(columnAssociationResolver).transform(
            InstanceSet.merge(listOf(conversionOutput, baseDataSet.instanceSet)),
        )
        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = CreateSelectColumnsForInstances(outputAlias, columnAssociationResolver)
                    .transform(outputInstanceSet).getColumns(null),
                fromSource = deduped,
                fromSourceAlias = outputAlias,
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

    /**
     * Port of `visit_semi_additive_join_node`.
     *
     * Builds a right-side sub-SELECT that aggregates the non-additive time dimension via MIN/MAX
     * grouped by entities (and optionally a queried time-dim window), then INNER-JOINs the
     * parent dataset back on the entity-set + the aggregated time-dim.
     */
    override fun visitSemiAdditiveJoinNode(node: SemiAdditiveJoinNode): SqlDataSet {
        val fromDataSet = getOutputDataSet(node.parentNode)
        val fromAlias = nextUniqueTableAlias()
        val outputInstanceSet = fromDataSet.instanceSet.transform(
            ChangeAssociatedColumns(columnAssociationResolver),
        )

        val innerJoinAlias = nextUniqueTableAlias()
        val columnEqualityDescriptions = mutableListOf<
            cc.monomer.metricflow.domain.plan_conversion.helpers.ColumnEqualityDescription,
            >()

        val timeDimensionColumnName = columnAssociationResolver.resolveSpec(node.timeDimensionSpec).columnName
        val joinTimeDimensionColumnName = columnAssociationResolver
            .resolveSpec(node.timeDimensionSpec.withAggregationState(AggregationState.COMPLETE))
            .columnName

        val timeDimensionSelectColumn = SqlSelectColumn(
            expr = SqlFunctionExpression.buildExpressionFromAggregationType(
                aggregationType = node.aggByFunction,
                sqlColumnExpression = SqlColumnReferenceExpression.create(
                    colRef = SqlColumnReference(
                        tableAlias = innerJoinAlias,
                        columnName = timeDimensionColumnName,
                    ),
                    shouldRenderTableAlias = true,
                ),
                aggParams = null,
            ),
            columnAlias = joinTimeDimensionColumnName,
        )
        columnEqualityDescriptions += cc.monomer.metricflow.domain.plan_conversion.helpers
            .ColumnEqualityDescription(
                leftColumnAlias = timeDimensionColumnName,
                rightColumnAlias = joinTimeDimensionColumnName,
                treatNullsAsEqual = false,
            )

        val entitySelectColumns = mutableListOf<SqlSelectColumn>()
        for (entityRef in node.entityReferences) {
            val entitySpec = cc.monomer.metricflow.domain.spec.EntitySpec(
                elementName = entityRef.elementName,
                entityLinks = emptyList(),
                alias = null,
            )
            val entityColumnName = columnAssociationResolver.resolveSpec(entitySpec).columnName
            entitySelectColumns += SqlSelectColumn(
                expr = SqlColumnReferenceExpression.fromColumnReference(
                    tableAlias = innerJoinAlias,
                    columnName = entityColumnName,
                ),
                columnAlias = entityColumnName,
            )
            columnEqualityDescriptions += cc.monomer.metricflow.domain.plan_conversion.helpers
                .ColumnEqualityDescription(
                    leftColumnAlias = entityColumnName,
                    rightColumnAlias = entityColumnName,
                    treatNullsAsEqual = false,
                )
        }

        val queriedTimeDimSelectColumn: SqlSelectColumn? = node.queriedTimeDimensionSpec?.let { qSpec ->
            val name = columnAssociationResolver.resolveSpec(qSpec).columnName
            SqlSelectColumn(
                expr = SqlColumnReferenceExpression.fromColumnReference(
                    tableAlias = innerJoinAlias,
                    columnName = name,
                ),
                columnAlias = name,
            )
        }

        val rowFilterGroupBys = entitySelectColumns + listOfNotNull(queriedTimeDimSelectColumn)
        val rightSourceSelectNode = SqlSelectStatementNode.create(
            description = "Filter row on ${node.aggByFunction.name}($timeDimensionColumnName)",
            selectColumns = rowFilterGroupBys + timeDimensionSelectColumn,
            fromSource = fromDataSet.checkedSqlSelectNode.copyNode(),
            fromSourceAlias = innerJoinAlias,
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = rowFilterGroupBys,
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val rightSourceAlias = nextUniqueTableAlias()
        val sqlJoinDesc = SqlPlanJoinBuilder.makeColumnEqualitySqlJoinDescription(
            rightSourceNode = rightSourceSelectNode,
            leftSourceAlias = fromAlias,
            rightSourceAlias = rightSourceAlias,
            columnEqualityDescriptions = columnEqualityDescriptions,
            joinType = SqlJoinType.INNER,
            additionalOnConditions = emptyList(),
        )
        val outputColumns = CreateSelectColumnsForInstances(
            tableAlias = fromAlias,
            columnResolver = columnAssociationResolver,
        ).transform(outputInstanceSet).getColumns(null)

        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = outputColumns,
                fromSource = fromDataSet.checkedSqlSelectNode,
                fromSourceAlias = fromAlias,
                cteSources = emptyList(),
                joinDescs = listOf(sqlJoinDesc),
                groupBys = emptyList(),
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )
    }

    /**
     * Port of `visit_window_reaggregation_node`.
     *
     * The pattern: build an inner SELECT that applies the window function over the metric column
     * (`FIRST_VALUE`/`LAST_VALUE`/`AVG` based on the `PeriodAggregation`), then wrap with an
     * outer SELECT that does the final GROUP BY (since window functions can't appear inside a
     * GROUP BY expression).
     */
    override fun visitWindowReaggregationNode(node: WindowReaggregationNode): SqlDataSet {
        val fromDataSet = getOutputDataSet(node.parentNode)
        val parentInstanceSet = fromDataSet.instanceSet
        val parentAlias = nextUniqueTableAlias()

        var metricInstance: MdoInstance? = null
        var orderByInstance: MdoInstance? = null
        val partitionByInstances = mutableListOf<MdoInstance>()
        for (instance in parentInstanceSet.asList) {
            val spec = instance.spec
            if (spec == node.metricSpec) {
                metricInstance = instance
            } else if (spec == node.orderBySpec) {
                orderByInstance = instance
            } else if (spec in node.partitionBySpecs) {
                partitionByInstances += instance
            }
        }
        val mi = checkNotNull(metricInstance) { "WindowReaggregationNode: missing metric instance for ${node.metricSpec}" }
        val oi = checkNotNull(orderByInstance) { "WindowReaggregationNode: missing order-by instance for ${node.orderBySpec}" }
        check(partitionByInstances.isNotEmpty()) {
            "WindowReaggregationNode: missing partition-by instances for ${node.partitionBySpecs}"
        }

        val metric = metricLookup.getMetric(node.metricSpec.reference)
        val periodAgg = metric.typeParams.cumulativeTypeParams?.periodAgg
            ?: cc.monomer.metricflow.domain.manifest.model.enums.PeriodAggregation.FIRST
        val windowFunction =
            cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowFunction.forPeriodAgg(periodAgg)

        val orderByArgs: List<cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowOrderByArgument> =
            if (windowFunction.requiresOrdering) {
                listOf(
                    cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowOrderByArgument(
                        expr = SqlColumnReferenceExpression.fromColumnReference(
                            tableAlias = parentAlias,
                            columnName = oi.associatedColumn.columnName,
                        ),
                        descending = null,
                        nullsLast = null,
                    ),
                )
            } else {
                emptyList()
            }
        val metricSelectColumn = SqlSelectColumn(
            expr = cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowFunctionExpression.create(
                sqlFunction = windowFunction,
                sqlFunctionArgs = listOf(
                    SqlColumnReferenceExpression.fromColumnReference(
                        tableAlias = parentAlias,
                        columnName = mi.associatedColumn.columnName,
                    ),
                ),
                partitionByArgs = partitionByInstances.map { pbInstance ->
                    SqlColumnReferenceExpression.fromColumnReference(
                        tableAlias = parentAlias,
                        columnName = pbInstance.associatedColumn.columnName,
                    )
                },
                orderByArgs = orderByArgs,
            ),
            columnAlias = mi.associatedColumn.columnName,
        )

        // Order-by instance is excluded from the output (it isn't a queried element).
        val orderBySpecForExclusion = oi.spec as? cc.monomer.metricflow.domain.spec.TimeDimensionSpec
            ?: error("WindowReaggregationNode expected a TimeDimensionSpec order_by; got ${oi.spec}")
        val outputInstanceSet = parentInstanceSet.transform(
            SelectElementsTransform(
                includeSpecs = null,
                excludeSpecs = cc.monomer.metricflow.domain.spec.InstanceSpecSet(
                    timeDimensionSpecs = listOf(orderBySpecForExclusion),
                    metricSpecs = emptyList(),
                    simpleMetricInputSpecs = emptyList(),
                    dimensionSpecs = emptyList(),
                    entitySpecs = emptyList(),
                    groupByMetricSpecs = emptyList(),
                    metadataSpecs = emptyList(),
                ),
            ),
        )

        // Build the inner subquery: pass-through columns + the window-function metric column.
        val passThroughInstanceSet = outputInstanceSet.transform(
            SelectElementsTransform(
                includeSpecs = null,
                excludeSpecs = cc.monomer.metricflow.domain.spec.InstanceSpecSet(
                    metricSpecs = listOf(node.metricSpec),
                    simpleMetricInputSpecs = emptyList(),
                    dimensionSpecs = emptyList(),
                    entitySpecs = emptyList(),
                    timeDimensionSpecs = emptyList(),
                    groupByMetricSpecs = emptyList(),
                    metadataSpecs = emptyList(),
                ),
            ),
        )
        val subqueryColumns = CreateSelectColumnsForInstances(
            tableAlias = parentAlias,
            columnResolver = columnAssociationResolver,
        ).transform(passThroughInstanceSet).getColumns(null) + metricSelectColumn

        val subquery = SqlSelectStatementNode.create(
            description = "Window Function for Metric Re-aggregation",
            selectColumns = subqueryColumns,
            fromSource = fromDataSet.checkedSqlSelectNode,
            fromSourceAlias = parentAlias,
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val subqueryAlias = nextUniqueTableAlias()
        val outerColumns = CreateSelectColumnsForInstances(
            tableAlias = subqueryAlias,
            columnResolver = columnAssociationResolver,
        ).transform(outputInstanceSet).getColumns(null)

        return SqlDataSet(
            instanceSet = outputInstanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = "Re-aggregate Metric via Group By",
                selectColumns = outerColumns,
                fromSource = subquery,
                fromSourceAlias = subqueryAlias,
                cteSources = emptyList(),
                joinDescs = emptyList(),
                groupBys = outerColumns,
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )
    }

    override fun visitOffsetCustomGranularityNode(node: OffsetCustomGranularityNode): SqlDataSet {
        val timeSpineDataSet = getOutputDataSet(node.timeSpineNode)
        val cteAlias = SequentialIdGenerator.createNextId(StaticIdPrefix.CTE).strValue
        val cte = SqlCteNode.create(timeSpineDataSet.checkedSqlSelectNode, cteAlias)
        val customName = node.offsetWindow.granularity
        val customInstance = timeSpineDataSet.instanceFromTimeDimensionGrainAndDatePart(customName, null)
        val customColumn = SqlSelectColumn.fromColumnReference(
            tableAlias = cteAlias,
            columnName = customInstance.associatedColumn.columnName,
        )
        val offsetInstance = customInstance.withNewSpec(
            customInstance.spec.withWindowFunctions(listOf(SqlWindowFunction.LEAD)),
            columnAssociationResolver,
        )
        val offsetColumn = SqlSelectColumn(
            expr = SqlWindowFunctionExpression.create(
                sqlFunction = SqlWindowFunction.LEAD,
                sqlFunctionArgs = listOf(customColumn.expr, SqlIntegerExpression.create(node.offsetWindow.count)),
                partitionByArgs = emptyList(),
                orderByArgs = listOf(SqlWindowOrderByArgument(customColumn.expr, null, null)),
            ),
            columnAlias = offsetInstance.associatedColumn.columnName,
        )
        val offsetSubquery = SqlSelectStatementNode.create(
            description = "Offset Custom Granularity",
            selectColumns = listOf(customColumn, offsetColumn),
            fromSource = SqlTableNode.create(cc.monomer.metricflow.domain.spec.bind.SqlTable(null, cteAlias)),
            fromSourceAlias = cteAlias,
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = listOf(customColumn),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val subqueryAlias = nextUniqueTableAlias()
        val join = SqlJoinDescription(
            rightSource = offsetSubquery,
            rightSourceAlias = subqueryAlias,
            joinType = SqlJoinType.INNER,
            onCondition = SqlComparisonExpression.create(
                leftExpr = customColumn.expr,
                comparison = SqlComparison.EQUALS,
                rightExpr = SqlColumnReferenceExpression.fromColumnReference(
                    subqueryAlias,
                    customInstance.associatedColumn.columnName,
                ),
            ),
        )
        val baseGrain = customTimeSpineSource(customName).baseGranularity
        val baseInstance = timeSpineDataSet.instanceFromTimeDimensionGrainAndDatePart(baseGrain.value, null)
        val baseColumn = SqlSelectColumn.fromColumnReference(cteAlias, baseInstance.associatedColumn.columnName)
        val requestedInstances = mutableListOf<TimeDimensionInstance>()
        val requestedColumns = mutableListOf<SqlSelectColumn>()
        val offsetReference = SqlColumnReferenceExpression.fromColumnReference(
            subqueryAlias,
            offsetColumn.columnAlias,
        )
        for (spec in node.requiredTimeSpineSpecs) {
            val newInstance = offsetInstance.withNewSpec(spec, columnAssociationResolver)
            requestedInstances += newInstance
            requestedColumns += SqlSelectColumn(offsetReference, newInstance.associatedColumn.columnName)
        }
        return SqlDataSet(
            instanceSet = InstanceSet(
                simpleMetricInputInstances = emptyList(),
                dimensionInstances = emptyList(),
                timeDimensionInstances = listOf(baseInstance) + requestedInstances,
                entityInstances = emptyList(),
                groupByMetricInstances = emptyList(),
                metricInstances = emptyList(),
                metadataInstances = emptyList(),
            ),
            sqlSelectNode = SqlSelectStatementNode.create(
                description = "Join Offset Custom Granularity to Base Granularity",
                selectColumns = listOf(baseColumn) + requestedColumns,
                fromSource = SqlTableNode.create(cc.monomer.metricflow.domain.spec.bind.SqlTable(null, cteAlias)),
                fromSourceAlias = cteAlias,
                cteSources = listOf(cte),
                joinDescs = listOf(join),
                groupBys = emptyList(),
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
        )
    }

    override fun visitOffsetBaseGrainByCustomGrainNode(node: OffsetBaseGrainByCustomGrainNode): SqlDataSet {
        val timeSpineDataSet = getOutputDataSet(node.timeSpineNode)
        val timeSpineAlias = nextUniqueTableAlias()
        val customName = node.offsetWindow.granularity
        val timeSpine = customTimeSpineSource(customName)
        val baseGrain = timeSpine.baseGranularity
        val customInstance = timeSpineDataSet.instanceFromTimeDimensionGrainAndDatePart(customName, null)
        val baseInstance = timeSpineDataSet.instanceFromTimeDimensionGrainAndDatePart(baseGrain.value, null)
        val customExpr = SqlColumnReferenceExpression.fromColumnReference(
            timeSpineAlias,
            customInstance.associatedColumn.columnName,
        )
        val baseExpr = SqlColumnReferenceExpression.fromColumnReference(
            timeSpineAlias,
            baseInstance.associatedColumn.columnName,
        )
        val boundColumns = mutableListOf<SqlSelectColumn>()
        val boundInstances = mutableListOf<TimeDimensionInstance>()
        for (windowFunction in listOf(SqlWindowFunction.FIRST_VALUE, SqlWindowFunction.LAST_VALUE)) {
            val boundInstance = customInstance.withNewSpec(
                customInstance.spec.withWindowFunctions(listOf(windowFunction)),
                columnAssociationResolver,
            )
            boundInstances += boundInstance
            boundColumns += SqlSelectColumn(
                expr = SqlWindowFunctionExpression.create(
                    sqlFunction = windowFunction,
                    sqlFunctionArgs = listOf(baseExpr),
                    partitionByArgs = listOf(customExpr),
                    orderByArgs = listOf(SqlWindowOrderByArgument(baseExpr, null, null)),
                ),
                columnAlias = boundInstance.associatedColumn.columnName,
            )
        }
        val rowNumberSpec = baseInstance.spec.withWindowFunctions(listOf(SqlWindowFunction.ROW_NUMBER))
        val rowNumberColumn = SqlSelectColumn(
            expr = SqlWindowFunctionExpression.create(
                sqlFunction = SqlWindowFunction.ROW_NUMBER,
                sqlFunctionArgs = emptyList(),
                partitionByArgs = listOf(customExpr),
                orderByArgs = listOf(SqlWindowOrderByArgument(baseExpr, null, null)),
            ),
            columnAlias = columnAssociationResolver.resolveSpec(rowNumberSpec).columnName,
        )
        val cteAlias = SequentialIdGenerator.createNextId(StaticIdPrefix.CTE).strValue
        val cte = SqlCteNode.create(
            SqlSelectStatementNode.create(
                description = "Get Custom Granularity Bounds",
                selectColumns = timeSpineDataSet.checkedSqlSelectNode.selectColumns + boundColumns + rowNumberColumn,
                fromSource = timeSpineDataSet.checkedSqlSelectNode,
                fromSourceAlias = timeSpineAlias,
                cteSources = emptyList(),
                joinDescs = emptyList(),
                groupBys = emptyList(),
                orderBys = emptyList(),
                where = null,
                limit = null,
                distinct = false,
            ),
            cteAlias,
        )
        val uniqueAlias = nextUniqueTableAlias()
        val uniqueColumns = listOf(customInstance.associatedColumn.columnName) + boundColumns.map { it.columnAlias }
        val uniqueSelectColumns = uniqueColumns.map { SqlSelectColumn.fromColumnReference(cteAlias, it) }
        val uniqueRows = SqlSelectStatementNode.create(
            description = "Get Unique Rows for Custom Granularity Bounds",
            selectColumns = uniqueSelectColumns,
            fromSource = SqlTableNode.create(cc.monomer.metricflow.domain.spec.bind.SqlTable(null, cteAlias)),
            fromSourceAlias = cteAlias,
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = uniqueSelectColumns,
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val uniqueCustomColumn = SqlSelectColumn.fromColumnReference(uniqueAlias, customInstance.associatedColumn.columnName)
        val offsetBoundColumns = boundColumns.mapIndexed { index, boundColumn ->
            val boundInstance = boundInstances[index]
            val offsetSpec = boundInstance.spec.withWindowFunctions(
                boundInstance.spec.windowFunctions + SqlWindowFunction.LEAD,
            )
            SqlSelectColumn(
                expr = SqlWindowFunctionExpression.create(
                    sqlFunction = SqlWindowFunction.LEAD,
                    sqlFunctionArgs = listOf(boundColumn.referenceFrom(uniqueAlias), SqlIntegerExpression.create(node.offsetWindow.count)),
                    partitionByArgs = emptyList(),
                    orderByArgs = listOf(SqlWindowOrderByArgument(uniqueCustomColumn.expr, null, null)),
                ),
                columnAlias = columnAssociationResolver.resolveSpec(offsetSpec).columnName,
            )
        }
        val offsetAlias = nextUniqueTableAlias()
        val offsetBounds = SqlSelectStatementNode.create(
            description = "Offset Custom Granularity Bounds",
            selectColumns = listOf(uniqueCustomColumn) + offsetBoundColumns,
            fromSource = uniqueRows,
            fromSourceAlias = uniqueAlias,
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val firstOffset = offsetBoundColumns[0].referenceFrom(offsetAlias)
        val lastOffset = offsetBoundColumns[1].referenceFrom(offsetAlias)
        val rowNumberRef = rowNumberColumn.referenceFrom(cteAlias)
        val offsetBaseExpr = SqlAddTimeExpression.create(
            arg = firstOffset,
            countExpr = SqlArithmeticExpression.create(
                leftExpr = rowNumberRef,
                operator = SqlArithmeticOperator.SUBTRACT,
                rightExpr = SqlIntegerExpression.create(1),
            ),
            granularity = baseGrain,
        )
        val offsetBaseColumnName = columnAssociationResolver.resolveSpec(
            baseInstance.spec.withWindowFunctions(listOf(SqlWindowFunction.LEAD)),
        ).columnName
        val offsetBaseColumn = SqlSelectColumn(
            expr = SqlCaseExpression.create(
                whenToThenExprs = linkedMapOf(
                    SqlComparisonExpression.create(
                        leftExpr = offsetBaseExpr,
                        comparison = SqlComparison.LESS_THAN_OR_EQUALS,
                        rightExpr = lastOffset,
                    ) to offsetBaseExpr,
                ),
                elseExpr = SqlNullExpression.create(),
            ),
            columnAlias = offsetBaseColumnName,
        )
        val originalBaseColumn = SqlSelectColumn.fromColumnReference(cteAlias, baseInstance.associatedColumn.columnName)
        val join = SqlJoinDescription(
            rightSource = offsetBounds,
            rightSourceAlias = offsetAlias,
            joinType = SqlJoinType.INNER,
            onCondition = SqlComparisonExpression.create(
                leftExpr = SqlColumnReferenceExpression.fromColumnReference(
                    cteAlias,
                    customInstance.associatedColumn.columnName,
                ),
                comparison = SqlComparison.EQUALS,
                rightExpr = uniqueCustomColumn.referenceFrom(offsetAlias),
            ),
        )
        val offsetSubquery = SqlSelectStatementNode.create(
            description = node.description,
            selectColumns = listOf(originalBaseColumn, offsetBaseColumn),
            fromSource = SqlTableNode.create(cc.monomer.metricflow.domain.spec.bind.SqlTable(null, cteAlias)),
            fromSourceAlias = cteAlias,
            cteSources = listOf(cte),
            joinDescs = listOf(join),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val offsetSubqueryAlias = nextUniqueTableAlias()
        val offsetBaseRef = offsetBaseColumn.referenceFrom(offsetSubqueryAlias)
        val requestedInstances = mutableListOf<TimeDimensionInstance>()
        val requestedColumns = mutableListOf<SqlSelectColumn>()
        for (spec in node.requiredTimeSpineSpecs) {
            val instance = baseInstance.withNewSpec(spec, columnAssociationResolver)
            val expression: SqlExpressionNode = when {
                spec.datePart != null -> SqlExtractExpression.create(spec.datePart, offsetBaseRef)
                spec.timeGranularity == null -> error("Required time-spine spec has no grain or date part: $spec")
                spec.timeGranularity.baseGranularity == baseGrain -> offsetBaseRef
                else -> SqlDateTruncExpression.create(spec.timeGranularity.baseGranularity, offsetBaseRef)
            }
            requestedInstances += instance
            requestedColumns += SqlSelectColumn(expression, instance.associatedColumn.columnName)
        }
        return SqlDataSet(
            instanceSet = InstanceSet(
                simpleMetricInputInstances = emptyList(),
                dimensionInstances = emptyList(),
                timeDimensionInstances = listOf(baseInstance) + requestedInstances,
                entityInstances = emptyList(),
                groupByMetricInstances = emptyList(),
                metricInstances = emptyList(),
                metadataInstances = emptyList(),
            ),
            sqlSelectNode = SqlSelectStatementNode.create(
                description = "Apply Requested Granularities",
                selectColumns = listOf(
                    SqlSelectColumn.fromColumnReference(offsetSubqueryAlias, baseInstance.associatedColumn.columnName),
                ) + requestedColumns,
                fromSource = offsetSubquery,
                fromSourceAlias = offsetSubqueryAlias,
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

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun makeSelect(
        description: String,
        selectColumns: List<SqlSelectColumn>,
        fromSource: SqlSelectStatementNode,
        fromSourceAlias: String,
    ): SqlSelectStatementNode = SqlSelectStatementNode.create(
        description = description,
        selectColumns = selectColumns,
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

    /** Port of `__make_col_reference_or_coalesce_expr`. */
    private fun makeColRefOrCoalesceExpr(
        columnName: String,
        nullFillValue: Int?,
        fromAlias: String,
    ): SqlExpressionNode {
        val ref: SqlExpressionNode = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(tableAlias = fromAlias, columnName = columnName),
            shouldRenderTableAlias = true,
        )
        if (nullFillValue == null) return ref
        return cc.monomer.metricflow.domain.sql.plan.expr.SqlAggregateFunctionExpression.create(
            sqlFunction = SqlFunction.COALESCE,
            sqlFunctionArgs = listOf(
                ref,
                SqlStringExpression.create(
                    sqlExpr = nullFillValue.toString(),
                    bindParameterSet =
                        cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet.EMPTY,
                    requiresParenthesis = false,
                    usedColumns = null,
                ),
            ),
        )
    }

    /** Port of `_render_where_constraint_expr`. */
    private fun renderWhereConstraintExpr(filterSpecs: List<WhereFilterSpec>): SqlExpressionNode? {
        val filterKeyToStringExpression = LinkedHashMap<
            Pair<String, cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet>,
            SqlStringExpression,
            >()
        for (spec in filterSpecs) {
            val key = spec.whereSql to spec.bindParameters
            if (key in filterKeyToStringExpression) continue
            val instanceSpecSet = InstanceSpecSet.createFromSpecs(spec.linkableSpecs)
            val columnAssociations = CreateColumnAssociations(columnAssociationResolver)
                .transform(instanceSpecSet)
            filterKeyToStringExpression[key] = SqlStringExpression.create(
                sqlExpr = spec.whereSql,
                bindParameterSet = spec.bindParameters,
                requiresParenthesis = true,
                usedColumns = columnAssociations.map { it.columnName },
            )
        }
        val stringExpressions = filterKeyToStringExpression.values.toList()
        return when (stringExpressions.size) {
            0 -> null
            1 -> stringExpressions[0]
            else -> SqlLogicalExpression.create(
                operator = SqlLogicalOperator.AND,
                args = stringExpressions,
            )
        }
    }

    /** Port of `_make_time_range_comparison_expr`. */
    private fun makeTimeRangeComparisonExpr(
        tableAlias: String,
        columnAlias: String,
        timeRangeConstraint: TimeRangeConstraint,
    ): SqlExpressionNode {
        val constraintUsesDayOrLargerGrain = listOf(
            timeRangeConstraint.startTime,
            timeRangeConstraint.endTime,
        ).all { it == it.toLocalDate().atStartOfDay() }

        val format = if (constraintUsesDayOrLargerGrain) ISO_DATE_FORMAT else ISO_TS_FORMAT
        return SqlBetweenExpression.create(
            columnArg = SqlColumnReferenceExpression.create(
                colRef = SqlColumnReference(tableAlias = tableAlias, columnName = columnAlias),
                shouldRenderTableAlias = true,
            ),
            startExpr = SqlStringLiteralExpression.create(
                literalValue = formatLocalDateTime(timeRangeConstraint.startTime, format),
            ),
            endExpr = SqlStringLiteralExpression.create(
                literalValue = formatLocalDateTime(timeRangeConstraint.endTime, format),
            ),
        )
    }

    private fun aliasInstance(instance: MdoInstance, newSpec: InstanceSpec): MdoInstance = when (instance) {
        is SimpleMetricInputInstance -> instance.withNewSpec(
            newSpec as cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec,
            columnAssociationResolver,
        )
        is DimensionInstance -> instance.withNewSpec(
            newSpec as cc.monomer.metricflow.domain.spec.DimensionSpec,
            columnAssociationResolver,
        )
        is TimeDimensionInstance -> instance.withNewSpec(
            newSpec as cc.monomer.metricflow.domain.spec.TimeDimensionSpec,
            columnAssociationResolver,
        )
        is EntityInstance -> instance.withNewSpec(
            newSpec as cc.monomer.metricflow.domain.spec.EntitySpec,
            columnAssociationResolver,
        )
        is MetricInstance -> instance.withNewSpec(newSpec as MetricSpec, columnAssociationResolver)
        is MetadataInstance -> instance.withNewSpec(newSpec as MetadataSpec, columnAssociationResolver)
        is GroupByMetricInstance -> instance.withNewSpec(
            newSpec as GroupByMetricSpec,
            columnAssociationResolver,
        )
    }

    private fun formatLocalDateTime(value: LocalDateTime, format: String): String =
        value.format(java.time.format.DateTimeFormatter.ofPattern(format))

    companion object {
        private const val ISO_DATE_FORMAT = "yyyy-MM-dd"
        private const val ISO_TS_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }
}
