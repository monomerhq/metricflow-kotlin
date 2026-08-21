package cc.monomer.metricflow.domain.plan_conversion.to_sql_plan

import cc.monomer.metricflow.common.dag.NodeId
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitorWithDefaultHandler
import cc.monomer.metricflow.domain.dataflow.dataset.SqlDataSet
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultDataTableNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultTableNode
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.plan_conversion.instance_transforms.CreateSelectColumnsForInstances
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import java.util.IdentityHashMap

/**
 * Parameters captured when a dataflow node is materialised as a CTE.
 *
 * Port of `metricflow.plan_conversion.to_sql_plan.dataflow_to_cte.CteGenerationResult`.
 * The generated read dataset has the same instance set as the CTE source and projects the
 * source columns through the CTE alias, so downstream conversion code can treat it like any
 * other dataflow source.
 */
data class CteGenerationResult(
    val sourceDataflowPlanNodeId: NodeId,
    val cteNode: SqlCteNode,
    val selectColumns: List<SqlSelectColumn>,
    val instanceSet: InstanceSet,
) {

    /** Return a dataset that reads the CTE through its public column aliases. */
    fun getSqlDataSet(): SqlDataSet {
        val cteAlias = cteNode.cteAlias
        return SqlDataSet(
            instanceSet = instanceSet,
            sqlSelectNode = SqlSelectStatementNode.create(
                description = "Read From CTE For node_id=$sourceDataflowPlanNodeId",
                selectColumns = selectColumns,
                fromSource = SqlTableNode.create(
                    SqlTable(schemaName = null, tableName = cteAlias),
                ),
                fromSourceAlias = cteAlias,
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
}

/**
 * Converts selected common dataflow branches into SQL CTEs.
 *
 * Port of `metricflow.plan_conversion.to_sql_plan.dataflow_to_cte.DataflowNodeToSqlCteVisitor`.
 * The Kotlin subquery visitor is intentionally a standalone, final implementation, so this
 * visitor preserves the upstream subclass behaviour by rewriting generated branches to
 * `ReadSqlSourceNode`s before delegating each rewritten root to the subquery visitor. This keeps
 * the conversion logic in one place while retaining upstream's observable SQL plan shape:
 * generated CTEs are collected in dependency order and attached to the final SELECT.
 *
 * A separate rewritten graph is used for each CTE source. It is not exposed to callers and does
 * not change the caller-owned dataflow nodes. Already-generated descendant CTEs are therefore
 * visible when a parent common branch is converted, matching the upstream visitor's
 * bottom-up traversal.
 */
class DataflowNodeToSqlCteVisitor(
    private val columnAssociationResolver: ColumnAssociationResolver,
    private val semanticManifestLookup: SemanticManifestLookup,
    private val nodesToConvertToCte: Set<DataflowPlanNode>,
    private val outputColumnOrderer: OutputColumnOrderer?,
) : DataflowPlanNodeVisitorWithDefaultHandler<SqlDataSet>() {

    private val nodeToCteGenerationResult: LinkedHashMap<DataflowPlanNode, CteGenerationResult> =
        LinkedHashMap()
    private val nodeToCteReadSource: LinkedHashMap<DataflowPlanNode, ReadSqlSourceNode> =
        LinkedHashMap()
    private val convertedRoots: IdentityHashMap<DataflowPlanNode, SqlDataSet> = IdentityHashMap()

    /** Return the CTE nodes generated while traversing the requested dataflow root. */
    fun generatedCteNodes(): List<SqlCteNode> = nodeToCteGenerationResult.values.map { it.cteNode }

    /**
     * Convert [dataflowPlanNode], collecting any requested common branches as CTEs.
     *
     * As in upstream, this returns the raw dataset. The converter attaches
     * [generatedCteNodes] to the top-level SELECT after traversal.
     */
    fun getOutputDataSet(dataflowPlanNode: DataflowPlanNode): SqlDataSet {
        val cached = convertedRoots[dataflowPlanNode]
        if (cached != null) return cached

        ensureCteGeneration(dataflowPlanNode)
        val rewrittenRoot = rewriteForCteRead(dataflowPlanNode, nodeBeingConverted = null)
        val dataSet = DataflowNodeToSqlSubqueryVisitor(
            columnAssociationResolver = columnAssociationResolver,
            semanticManifestLookup = semanticManifestLookup,
            outputColumnOrderer = outputColumnOrderer,
        ).getOutputDataSet(rewrittenRoot)
        convertedRoots[dataflowPlanNode] = dataSet
        return dataSet
    }

    /** Convert [dataflowPlanNode] and attach all generated CTEs to its SQL root. */
    fun getOutputDataSetWithCtes(dataflowPlanNode: DataflowPlanNode): SqlDataSet =
        attachGeneratedCtes(getOutputDataSet(dataflowPlanNode))

    override fun defaultHandler(node: DataflowPlanNode): SqlDataSet = getOutputDataSet(node)

    /** Generate all reachable requested CTEs from deepest parent to closest sink. */
    private fun ensureCteGeneration(root: DataflowPlanNode) {
        val visited = IdentityHashMap<DataflowPlanNode, Boolean>()
        val nodesInDependencyOrder = mutableListOf<DataflowPlanNode>()

        fun collect(node: DataflowPlanNode) {
            if (visited.put(node, true) != null) return
            for (parent in node.parentNodes) collect(parent)
            if (node in nodesToConvertToCte && !nodeToCteGenerationResult.containsKey(node)) {
                nodesInDependencyOrder.add(node)
            }
        }

        collect(root)
        for (node in nodesInDependencyOrder) generateCte(node)
    }

    /** Materialise one common branch, after replacing already-generated descendant CTEs. */
    private fun generateCte(node: DataflowPlanNode) {
        if (nodeToCteGenerationResult.containsKey(node)) return

        val rewrittenNode = rewriteForCteRead(node, nodeBeingConverted = node)
        val sourceDataSet = DataflowNodeToSqlSubqueryVisitor(
            columnAssociationResolver = columnAssociationResolver,
            semanticManifestLookup = semanticManifestLookup,
            outputColumnOrderer = outputColumnOrderer,
        ).getOutputDataSet(rewrittenNode)

        val cteAlias = "${node.nodeId.idStr}_${StaticIdPrefix.CTE.strValue}"
        require(generatedCteNodes().none { it.cteAlias == cteAlias }) {
            "$cteAlias is a duplicate of one already generated for this dataflow plan."
        }

        val cteNode = SqlCteNode.create(
            selectStatement = sourceDataSet.sqlNode,
            cteAlias = cteAlias,
        )
        val selectColumns = CreateSelectColumnsForInstances(
            tableAlias = cteAlias,
            columnResolver = columnAssociationResolver,
        ).transform(sourceDataSet.instanceSet).getColumns(
            if (usesOutputColumnOrderer(node)) outputColumnOrderer else null,
        )
        val generationResult = CteGenerationResult(
            sourceDataflowPlanNodeId = node.nodeId,
            cteNode = cteNode,
            selectColumns = selectColumns,
            instanceSet = sourceDataSet.instanceSet,
        )
        nodeToCteGenerationResult[node] = generationResult
        nodeToCteReadSource[node] = ReadSqlSourceNode(generationResult.getSqlDataSet())
    }

    /**
     * Replace generated branches with source nodes that read their CTE aliases.
     *
     * [nodeBeingConverted] is left intact while its CTE body is built. Every other generated
     * branch is replaced, including a generated root when the final query is built.
     */
    private fun rewriteForCteRead(
        root: DataflowPlanNode,
        nodeBeingConverted: DataflowPlanNode?,
    ): DataflowPlanNode {
        val rewrittenNodes = IdentityHashMap<DataflowPlanNode, DataflowPlanNode>()

        fun rewrite(node: DataflowPlanNode): DataflowPlanNode {
            if (node !== nodeBeingConverted) {
                val cteReadSource = nodeToCteReadSource[node]
                if (cteReadSource != null) return cteReadSource
            }

            val cached = rewrittenNodes[node]
            if (cached != null) return cached

            val rewrittenParents = node.parentNodes.map(::rewrite)
            val rewritten = if (sameNodeReferences(node.parentNodes, rewrittenParents)) {
                node
            } else {
                node.withNewParents(rewrittenParents)
            }
            rewrittenNodes[node] = rewritten
            return rewritten
        }

        return rewrite(root)
    }

    private fun sameNodeReferences(
        original: List<DataflowPlanNode>,
        rewritten: List<DataflowPlanNode>,
    ): Boolean = original.size == rewritten.size && original.indices.all { original[it] === rewritten[it] }

    private fun usesOutputColumnOrderer(node: DataflowPlanNode): Boolean =
        node is WriteToResultDataTableNode || node is WriteToResultTableNode

    /** Put all generated CTEs on the top-level SELECT, as upstream does after visitor traversal. */
    private fun attachGeneratedCtes(dataSet: SqlDataSet): SqlDataSet {
        val generatedCtes = generatedCteNodes()
        if (generatedCtes.isEmpty()) return dataSet

        return when (val sqlNode = dataSet.sqlNode) {
            is SqlSelectStatementNode -> SqlDataSet(
                instanceSet = dataSet.instanceSet,
                sqlSelectNode = sqlNode.withCteSources(generatedCtes),
            )
            is SqlCreateTableAsNode -> {
                val parentSelect = sqlNode.parentNode as? SqlSelectStatementNode
                    ?: error("Expected CTAS parent to be a SELECT when attaching generated CTEs.")
                SqlDataSet(
                    instanceSet = dataSet.instanceSet,
                    sqlNode = SqlCreateTableAsNode.create(
                        sqlTable = sqlNode.sqlTable,
                        parentNode = parentSelect.withCteSources(generatedCtes),
                    ),
                )
            }
            else -> error(
                "Expected the dataflow SQL root to be a SELECT or CTAS when attaching CTEs, " +
                    "but got ${sqlNode::class.simpleName}.",
            )
        }
    }
}

/** Return a SELECT with [cteSources] while preserving all other SQL-plan fields. */
private fun SqlSelectStatementNode.withCteSources(cteSources: List<SqlCteNode>): SqlSelectStatementNode =
    SqlSelectStatementNode.create(
        description = description,
        selectColumns = selectColumns,
        fromSource = fromSource,
        fromSourceAlias = fromSourceAlias,
        cteSources = cteSources,
        joinDescs = joinDescs,
        groupBys = groupBys,
        orderBys = orderBys,
        where = where,
        limit = limit,
        distinct = distinct,
    )
