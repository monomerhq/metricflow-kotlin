package cc.monomer.metricflow.domain.dataflow.dataset

import cc.monomer.metricflow.domain.dataflow.instance.EntityInstance
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.instance.MdoInstance
import cc.monomer.metricflow.domain.dataflow.instance.TimeDimensionInstance
import cc.monomer.metricflow.domain.dataflow.support.SqlDataSet as SqlDataSetInterface
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode

/**
 * A metric data set along with the SQL plan node that materialises those values.
 *
 * Port of `metricflow.dataset.sql_dataset.SqlDataSet`. Carries:
 *
 * - an [InstanceSet] describing the columns flowing through the dataflow plan, and
 * - either a [SqlSelectStatementNode] (the common case — most plan nodes wrap a SELECT) or an
 *   arbitrary [SqlPlanNode] (used for source-table reads and CTEs).
 *
 * Exactly one of the two SQL-node fields is set (Python uses `assert_exactly_one_arg_set`).
 *
 * Also implements the W9a [SqlDataSetInterface] marker so that
 * [cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode] can keep using the
 * lighter-weight type from the node API while concrete plans flow this class through.
 */
open class SqlDataSet(
    instanceSet: InstanceSet,
    private val _sqlSelectNode: SqlSelectStatementNode?,
    private val _sqlNode: SqlPlanNode?,
) : DataSet(instanceSet), SqlDataSetInterface {

    init {
        val bothSet = _sqlSelectNode != null && _sqlNode != null
        val neitherSet = _sqlSelectNode == null && _sqlNode == null
        require(!bothSet && !neitherSet) {
            "Exactly one of `sqlSelectNode` / `sqlNode` must be set " +
                "(sqlSelectNode=$_sqlSelectNode, sqlNode=$_sqlNode)"
        }
    }

    /** Convenience constructor for the select-statement case. */
    constructor(instanceSet: InstanceSet, sqlSelectNode: SqlSelectStatementNode) : this(
        instanceSet = instanceSet,
        _sqlSelectNode = sqlSelectNode,
        _sqlNode = null,
    )

    /** Convenience constructor for the generic SQL plan node case. */
    constructor(instanceSet: InstanceSet, sqlNode: SqlPlanNode) : this(
        instanceSet = instanceSet,
        _sqlSelectNode = sqlNode as? SqlSelectStatementNode,
        _sqlNode = if (sqlNode is SqlSelectStatementNode) null else sqlNode,
    )

    /** The wrapped SQL plan node, whichever variant was supplied. */
    val sqlNode: SqlPlanNode
        get() = _sqlSelectNode ?: _sqlNode
            ?: error("Internal: SqlDataSet has neither sqlSelectNode nor sqlNode.")

    /**
     * If applicable, return the SELECT node that can be used to read data from the underlying
     * table / SQL query. Throws if this dataset wraps a non-select node.
     */
    val checkedSqlSelectNode: SqlSelectStatementNode
        get() = _sqlSelectNode
            ?: error("${this::class.simpleName} was created with a SQL node that is not a SqlSelectStatementNode")

    /** Source datasets (W9b) never carry a semantic-model reference. Overridden in [SemanticModelDataSet]. */
    override val semanticModelReference: SemanticModelReference? get() = null

    /**
     * Given the name of the entity, return the set of columns associated with it. Port of
     * `column_associations_for_entity`.
     *
     * Prefers instances whose entity links match (i.e. no links — the "local" entity) but falls
     * back to mismatched-link instances if no link-free match exists.
     */
    fun columnAssociationsForEntity(entityReference: EntityReference): List<ColumnAssociation> {
        val matchingSameLinks = mutableListOf<EntityInstance>()
        val matchingDifferentLinks = mutableListOf<EntityInstance>()
        for (instance in instanceSet.entityInstances) {
            if (entityReference.elementName == instance.spec.elementName) {
                if (instance.spec.entityLinks.isEmpty()) matchingSameLinks.add(instance)
                else matchingDifferentLinks.add(instance)
            }
        }
        val matching = if (matchingSameLinks.isNotEmpty()) matchingSameLinks else matchingDifferentLinks
        check(matching.size == 1) {
            "Expected exactly one matching instance for $entityReference in instance set, but found: " +
                "$matching. All entity instances: ${instanceSet.entityInstances}"
        }
        val instance = matching[0]
        check(instance.associatedColumns.isNotEmpty()) {
            "No associated columns for entity instance $instance in data set. This indicates internal misconfiguration."
        }
        return instance.associatedColumns
    }

    /** Port of `column_association_for_dimension`. Throws if zero or multiple matches found. */
    fun columnAssociationForDimension(dimensionSpec: DimensionSpec): ColumnAssociation {
        var matched: List<ColumnAssociation>? = null
        var matchCount = 0
        for (instance in instanceSet.dimensionInstances) {
            if (instance.spec == dimensionSpec) {
                matched = instance.associatedColumns
                matchCount += 1
            }
        }
        check(matchCount <= 1) {
            "More than one dimension instance with spec $dimensionSpec in instance set: $instanceSet"
        }
        check(matched != null) {
            "No dimension instances with spec $dimensionSpec in instance set: $instanceSet"
        }
        return matched[0]
    }

    /** Port of `instances_for_time_dimensions`. */
    fun instancesForTimeDimensions(timeDimensionSpecs: List<TimeDimensionSpec>): List<TimeDimensionInstance> {
        val specSet = timeDimensionSpecs.toSet()
        val matches = mutableListOf<TimeDimensionInstance>()
        for (instance in instanceSet.timeDimensionInstances) {
            if (instance.spec in specSet) matches.add(instance)
        }
        check(matches.size == specSet.size) {
            "Unexpected number of time dimension instances found matching specs. " +
                "Specs: $specSet. Instances: $matches"
        }
        return matches
    }

    /** Port of `instance_for_time_dimension`. */
    fun instanceForTimeDimension(timeDimensionSpec: TimeDimensionSpec): TimeDimensionInstance {
        val results = instancesForTimeDimensions(listOf(timeDimensionSpec))
        check(results.size == 1) {
            "Unexpected number of time dimension instances found matching specs. " +
                "Specs: $timeDimensionSpec. Instances: $results"
        }
        return results[0]
    }

    /** Port of `instance_for_spec`. */
    fun instanceForSpec(spec: InstanceSpec): MdoInstance {
        for (instance in instanceSet.asList) {
            if (instance.spec == spec) return instance
        }
        error("Did not find instance matching spec in dataset. spec=$spec instances=${instanceSet.asList}")
    }

    /** Port of `instance_for_column_name`. */
    fun instanceForColumnName(columnName: String): MdoInstance {
        for (instance in instanceSet.asList) {
            if (instance.associatedColumn.columnName == columnName) return instance
        }
        error("Did not find instance matching column name in dataset. columnName=$columnName instances=${instanceSet.asList}")
    }

    /** Port of `instance_from_time_dimension_grain_and_date_part`. */
    fun instanceFromTimeDimensionGrainAndDatePart(
        timeGranularityName: String?,
        datePart: DatePart?,
    ): TimeDimensionInstance {
        for (instance in instanceSet.timeDimensionInstances) {
            if (instance.spec.timeGranularityName == timeGranularityName &&
                instance.spec.datePart == datePart &&
                instance.spec.windowFunctions.isEmpty()
            ) {
                return instance
            }
        }
        error(
            "Did not find a time dimension instance with grain and date part in dataset. " +
                "timeGranularityName=$timeGranularityName datePart=$datePart " +
                "instancesAvailable=${instanceSet.timeDimensionInstances}",
        )
    }

    /** Port of `column_association_for_time_dimension`. */
    fun columnAssociationForTimeDimension(timeDimensionSpec: TimeDimensionSpec): ColumnAssociation =
        instanceForTimeDimension(timeDimensionSpec).associatedColumn

    /** Convert this dataset to an [AnnotatedSqlDataSet] with the supplied alias and metric-time spec. */
    fun annotate(alias: String, metricTimeSpec: TimeDimensionSpec): AnnotatedSqlDataSet {
        val metricTimeColumnName = columnAssociationForTimeDimension(metricTimeSpec).columnName
        return AnnotatedSqlDataSet(dataSet = this, alias = alias, metricTimeColumnName = metricTimeColumnName)
    }
}

/**
 * Binds a [SqlDataSet] to transient properties (alias / metric-time column name) used during
 * dataflow→SQL conversion. Port of `metricflow.dataset.sql_dataset.AnnotatedSqlDataSet`.
 *
 * The metric-time column name is optional in Python (`_metric_time_column_name`); we model it
 * the same way and surface a non-null accessor that panics when not set, matching the Python
 * `assert`.
 */
data class AnnotatedSqlDataSet(
    val dataSet: SqlDataSet,
    val alias: String,
    private val metricTimeColumnName: String?,
) {
    /** Convenience constructor: alias-only, no metric-time column. */
    constructor(dataSet: SqlDataSet, alias: String) : this(dataSet, alias, null)

    /** Returns the metric-time column name; fails when not set. */
    val checkedMetricTimeColumnName: String
        get() = metricTimeColumnName
            ?: error("Expected a valid metric time dimension name to be associated with this dataset, but did not get one!")
}
