package cc.monomer.metricflow.domain.plan_conversion.instance_transforms

import cc.monomer.metricflow.domain.dataflow.builder.InstanceAliasMapping
import cc.monomer.metricflow.domain.dataflow.instance.DimensionInstance
import cc.monomer.metricflow.domain.dataflow.instance.EntityInstance
import cc.monomer.metricflow.domain.dataflow.instance.GroupByMetricInstance
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSetTransform
import cc.monomer.metricflow.domain.dataflow.instance.MetadataInstance
import cc.monomer.metricflow.domain.dataflow.instance.MetricInstance
import cc.monomer.metricflow.domain.dataflow.instance.SimpleMetricInputInstance
import cc.monomer.metricflow.domain.dataflow.instance.TimeDimensionInstance
import cc.monomer.metricflow.domain.dataflow.nodes.ValidityWindowJoinDescription
import cc.monomer.metricflow.domain.dataflow.support.NullFillValueMapping
import cc.monomer.metricflow.domain.lookup.MetricLookup
import cc.monomer.metricflow.domain.lookup.SemanticModelLookup
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.plan_conversion.helpers.SelectColumnSet
import cc.monomer.metricflow.domain.spec.AggregationState
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAggregateFunctionExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.expr.SqlFunction
import cc.monomer.metricflow.domain.sql.plan.expr.SqlFunctionExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringExpression
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet

// =============================================================================================
// CreateValidityWindowJoinDescription
// =============================================================================================

/**
 * Inspect an [InstanceSet] and build a [ValidityWindowJoinDescription] for SCD Type II joins.
 *
 * Port of `metricflow.plan_conversion.instance_set_transforms.instance_converters
 * .CreateValidityWindowJoinDescription`.
 *
 * Returns `null` if no validity window dimensions are present. Throws if more than one
 * semantic model in the input set carries a validity window (joining across SCD models is not
 * supported).
 */
class CreateValidityWindowJoinDescription(
    private val semanticModelLookup: SemanticModelLookup,
) : InstanceSetTransform<ValidityWindowJoinDescription?> {

    private data class DimensionValidityParams(
        val dimensionName: String,
        val timeGranularity: TimeGranularity,
        val datePart: DatePart?,
    )

    override fun transform(instanceSet: InstanceSet): ValidityWindowJoinDescription? {
        val semanticModelToWindow = LinkedHashMap<SemanticModelReference, ValidityWindowJoinDescription>()

        // Bucket time-dimension instances by their origin semantic-model reference.
        val instancesBySemanticModel = LinkedHashMap<SemanticModelReference, MutableList<TimeDimensionInstance>>()
        for (instance in instanceSet.timeDimensionInstances) {
            val key = instance.originSemanticModelReference.semanticModelReference
            instancesBySemanticModel.getOrPut(key) { mutableListOf() }.add(instance)
        }

        for ((semanticModelReference, instances) in instancesBySemanticModel) {
            val validityDims = getValidityWindowDimensionsForSemanticModel(semanticModelReference) ?: continue
            val (startDim, endDim) = validityDims

            val specs = instances.map { it.spec }.toSet()
            val startSpecs = specs.filter { spec ->
                spec.elementName == startDim.dimensionName &&
                    !spec.hasCustomGrain &&
                    spec.baseGranularity == startDim.timeGranularity &&
                    spec.datePart == startDim.datePart
            }
            val endSpecs = specs.filter { spec ->
                spec.elementName == endDim.dimensionName &&
                    !spec.hasCustomGrain &&
                    spec.baseGranularity == endDim.timeGranularity &&
                    spec.datePart == endDim.datePart
            }
            val linklessStartSpecs = startSpecs.map { it.withoutEntityLinks() }.toSet()
            val linklessEndSpecs = endSpecs.map { it.withoutEntityLinks() }.toSet()
            check(linklessStartSpecs.size == 1 && linklessEndSpecs.size == 1) {
                "Did not find exactly one pair of specs from semantic model `$semanticModelReference` " +
                    "matching the validity window end points defined in the semantic model. This means " +
                    "we cannot process an SCD join, because we require exactly one validity window to be " +
                    "specified for the query! The window in the semantic model is defined by start " +
                    "dimension `$startDim` and end dimension `$endDim`. We found ${linklessStartSpecs.size} " +
                    "linkless specs for window start ($linklessStartSpecs) and ${linklessEndSpecs.size} " +
                    "linkless specs for window end ($linklessEndSpecs)."
            }
            val sortedStartSpecs = startSpecs.sortedBy { it.entityLinks.size }
            val sortedEndSpecs = endSpecs.sortedBy { it.entityLinks.size }
            semanticModelToWindow[semanticModelReference] = ValidityWindowJoinDescription(
                windowStartDimension = sortedStartSpecs[0],
                windowEndDimension = sortedEndSpecs[0],
            )
        }

        check(semanticModelToWindow.size < 2) {
            "Found more than 1 set of validity window specs in the input instance set. This is not " +
                "currently supported, as joins between SCD semantic models are not yet allowed! " +
                "$semanticModelToWindow"
        }

        return semanticModelToWindow.values.firstOrNull()
    }

    private fun getValidityWindowDimensionsForSemanticModel(
        semanticModelReference: SemanticModelReference,
    ): Pair<DimensionValidityParams, DimensionValidityParams>? {
        val semanticModel = semanticModelLookup.getByReference(semanticModelReference)
            ?: error("Could not find semantic model $semanticModelReference after data set conversion!")

        val startDim = semanticModel.validityStartDimension
        val endDim = semanticModel.validityEndDimension
        if (startDim == null || endDim == null) return null

        val startTypeParams = checkNotNull(startDim.typeParams) {
            "Validity info cannot exist without type params"
        }
        val endTypeParams = checkNotNull(endDim.typeParams) {
            "Validity info cannot exist without type params"
        }
        val startGrain = checkNotNull(startTypeParams.timeGranularity) {
            "Validity-start dimension `${startDim.name}` is missing a time granularity"
        }
        val endGrain = checkNotNull(endTypeParams.timeGranularity) {
            "Validity-end dimension `${endDim.name}` is missing a time granularity"
        }

        return DimensionValidityParams(
            dimensionName = startDim.name,
            timeGranularity = startGrain,
            datePart = null,
        ) to DimensionValidityParams(
            dimensionName = endDim.name,
            timeGranularity = endGrain,
            datePart = null,
        )
    }
}

// =============================================================================================
// FilterLinkableInstancesWithLeadingLink
// =============================================================================================

/**
 * Return an instance set with linkable instances whose leading entity link matches a given
 * reference removed.
 *
 * Port of `FilterLinkableInstancesWithLeadingLink`. Example — remove `listing__country` if the
 * specified link is `listing`.
 */
class FilterLinkableInstancesWithLeadingLink(
    private val entityLink: cc.monomer.metricflow.domain.manifest.model.references.EntityReference,
) : InstanceSetTransform<InstanceSet> {

    private fun shouldPass(linkableSpec: cc.monomer.metricflow.domain.spec.LinkableInstanceSpec): Boolean {
        if (linkableSpec.entityLinks.isEmpty()) return linkableSpec.reference != entityLink
        return linkableSpec.entityLinks[0] != entityLink
    }

    override fun transform(instanceSet: InstanceSet): InstanceSet = InstanceSet(
        simpleMetricInputInstances = instanceSet.simpleMetricInputInstances,
        dimensionInstances = instanceSet.dimensionInstances.filter { shouldPass(it.spec) },
        timeDimensionInstances = instanceSet.timeDimensionInstances.filter { shouldPass(it.spec) },
        entityInstances = instanceSet.entityInstances.filter { shouldPass(it.spec) },
        groupByMetricInstances = instanceSet.groupByMetricInstances.filter { shouldPass(it.spec) },
        metricInstances = instanceSet.metricInstances,
        metadataInstances = instanceSet.metadataInstances,
    )
}

// =============================================================================================
// SelectElementsTransform
// =============================================================================================

/**
 * Return an [InstanceSet] containing only instances matching the supplied include / exclude
 * spec set. Exactly one of [includeSpecs] / [excludeSpecs] must be non-null.
 *
 * Port of `SelectElementsTransform`.
 */
class SelectElementsTransform(
    private val includeSpecs: InstanceSpecSet?,
    private val excludeSpecs: InstanceSpecSet?,
) : InstanceSetTransform<InstanceSet> {

    init {
        val included = includeSpecs != null
        val excluded = excludeSpecs != null
        require(included xor excluded) {
            "Exactly one of includeSpecs / excludeSpecs must be set (includeSpecs=$includeSpecs, " +
                "excludeSpecs=$excludeSpecs)."
        }
    }

    private fun shouldPass(elementSpec: InstanceSpec): Boolean {
        val include = includeSpecs
        if (include != null) return include.allSpecs.any { it == elementSpec }
        val exclude = checkNotNull(excludeSpecs)
        return exclude.allSpecs.none { it == elementSpec }
    }

    override fun transform(instanceSet: InstanceSet): InstanceSet {
        val availableSpecs = instanceSet.specSet.allSpecs
        when {
            includeSpecs != null -> {
                val missing = includeSpecs.allSpecs.filter { it !in availableSpecs }
                check(missing.isEmpty()) {
                    "Include specs are not in the spec set - check if this node was constructed " +
                        "correctly. missing=$missing available=$availableSpecs"
                }
            }
            excludeSpecs != null -> {
                val missing = excludeSpecs.allSpecs.filter { it !in availableSpecs }
                check(missing.isEmpty()) {
                    "Exclude specs are not in the spec set - check if this node was constructed " +
                        "correctly. missing=$missing available=$availableSpecs"
                }
            }
        }
        return InstanceSet(
            simpleMetricInputInstances = instanceSet.simpleMetricInputInstances.filter { shouldPass(it.spec) },
            dimensionInstances = instanceSet.dimensionInstances.filter { shouldPass(it.spec) },
            timeDimensionInstances = instanceSet.timeDimensionInstances.filter { shouldPass(it.spec) },
            entityInstances = instanceSet.entityInstances.filter { shouldPass(it.spec) },
            groupByMetricInstances = instanceSet.groupByMetricInstances.filter { shouldPass(it.spec) },
            metricInstances = instanceSet.metricInstances.filter { shouldPass(it.spec) },
            metadataInstances = instanceSet.metadataInstances.filter { shouldPass(it.spec) },
        )
    }
}

// =============================================================================================
// ChangeSimpleMetricInputAggregationState
// =============================================================================================

/**
 * Re-bucket simple-metric-input instances under a new [AggregationState].
 *
 * Port of `ChangeSimpleMetricInputAggregationState`.
 */
class ChangeSimpleMetricInputAggregationState(
    private val aggregationStateChanges: Map<AggregationState, AggregationState>,
) : InstanceSetTransform<InstanceSet> {

    override fun transform(instanceSet: InstanceSet): InstanceSet {
        for (instance in instanceSet.simpleMetricInputInstances) {
            check(instance.aggregationState in aggregationStateChanges) {
                "Aggregation state: ${instance.aggregationState} not handled in change dict: $aggregationStateChanges"
            }
        }
        val instances = instanceSet.simpleMetricInputInstances.map {
            SimpleMetricInputInstance(
                associatedColumns = it.associatedColumns,
                definedFrom = it.definedFrom,
                aggregationState = aggregationStateChanges.getValue(it.aggregationState),
                spec = it.spec,
            )
        }
        return instanceSet.copy(simpleMetricInputInstances = instances)
    }
}

// =============================================================================================
// UpdateSimpleMetricInputFillNullsWith
// =============================================================================================

/**
 * Assign a `fillNullsWith` value to simple-metric-input specs whose element name appears in
 * [nullFillValueMapping].
 *
 * Port of `UpdateSimpleMetricInputFillNullsWith`.
 */
class UpdateSimpleMetricInputFillNullsWith(
    private val nullFillValueMapping: NullFillValueMapping,
) : InstanceSetTransform<InstanceSet> {

    override fun transform(instanceSet: InstanceSet): InstanceSet {
        val updated = instanceSet.simpleMetricInputInstances.map { instance ->
            val mappedSpec = nullFillValueMapping.nullFillValueSpec(instance.spec)
            if (mappedSpec != null) {
                SimpleMetricInputInstance(
                    associatedColumns = instance.associatedColumns,
                    spec = mappedSpec,
                    aggregationState = instance.aggregationState,
                    definedFrom = instance.definedFrom,
                )
            } else {
                instance
            }
        }
        return instanceSet.copy(simpleMetricInputInstances = updated)
    }
}

// =============================================================================================
// AliasAggregatedSimpleMetricInputs
// =============================================================================================

/**
 * Apply an [InstanceAliasMapping] to the simple-metric-input instances, replacing element names
 * with their declared aliases.
 *
 * Port of `AliasAggregatedSimpleMetricInputs`.
 */
class AliasAggregatedSimpleMetricInputs(
    private val aliasMapping: InstanceAliasMapping,
) : InstanceSetTransform<InstanceSet> {

    override fun transform(instanceSet: InstanceSet): InstanceSet {
        val aliased = instanceSet.simpleMetricInputInstances.map { instance ->
            val aliasedSpec = aliasMapping.aliasedSpec(instance.spec)
            if (aliasedSpec != null) {
                SimpleMetricInputInstance(
                    associatedColumns = instance.associatedColumns,
                    spec = aliasedSpec,
                    aggregationState = instance.aggregationState,
                    definedFrom = instance.definedFrom,
                )
            } else {
                instance
            }
        }
        return instanceSet.copy(simpleMetricInputInstances = aliased)
    }
}

// =============================================================================================
// AddMetrics, AddGroupByMetric, AddMetadata, ConvertToMetadata, RemoveSimpleMetricInput, RemoveMetrics
// =============================================================================================

/** Append [metricInstances] to the given [InstanceSet]. Port of `AddMetrics`. */
class AddMetrics(private val metricInstances: List<MetricInstance>) : InstanceSetTransform<InstanceSet> {
    override fun transform(instanceSet: InstanceSet): InstanceSet =
        instanceSet.copy(metricInstances = instanceSet.metricInstances + metricInstances)
}

/** Append a [GroupByMetricInstance] to the set. Port of `AddGroupByMetric`. */
class AddGroupByMetric(private val groupByMetricInstance: GroupByMetricInstance) :
    InstanceSetTransform<InstanceSet> {
    override fun transform(instanceSet: InstanceSet): InstanceSet =
        instanceSet.copy(
            groupByMetricInstances = instanceSet.groupByMetricInstances + groupByMetricInstance,
        )
}

/** Remove every simple-metric-input from the set. Port of `RemoveSimpleMetricInputTransform`. */
class RemoveSimpleMetricInputTransform : InstanceSetTransform<InstanceSet> {
    override fun transform(instanceSet: InstanceSet): InstanceSet =
        instanceSet.copy(simpleMetricInputInstances = emptyList())
}

/**
 * Remove all metrics from the set except those marked for retention.
 *
 * Port of `RemoveMetrics`.
 */
class RemoveMetrics(retainedMetricSpecs: Iterable<MetricSpec>) : InstanceSetTransform<InstanceSet> {
    private val retainedMetricSpecs: Set<MetricSpec> = retainedMetricSpecs.toSet()

    override fun transform(instanceSet: InstanceSet): InstanceSet = instanceSet.copy(
        metricInstances = instanceSet.metricInstances.filter { it.spec in retainedMetricSpecs },
    )
}

// =============================================================================================
// CreateSqlColumnReferencesForInstances
// =============================================================================================

/**
 * Create [SqlColumnReferenceExpression]s addressing every instance in the set.
 *
 * Port of `CreateSqlColumnReferencesForInstances`. Uses the supplied [columnResolver] to name
 * each column, drawing them all under [tableAlias].
 */
class CreateSqlColumnReferencesForInstances(
    private val tableAlias: String,
    private val columnResolver: ColumnAssociationResolver,
) : InstanceSetTransform<List<SqlColumnReferenceExpression>> {

    override fun transform(instanceSet: InstanceSet): List<SqlColumnReferenceExpression> {
        val columnNames = instanceSet.specSet.allSpecs.map { columnResolver.resolveSpec(it).columnName }
        return columnNames.map { name ->
            SqlColumnReferenceExpression.create(
                colRef = SqlColumnReference(tableAlias = tableAlias, columnName = name),
                shouldRenderTableAlias = true,
            )
        }
    }
}

// =============================================================================================
// CreateSelectColumnForCombineOutputNode
// =============================================================================================

/**
 * Create SELECT columns suitable for the `CombineAggregatedOutputsNode` join, applying MAX
 * aggregation + optional `COALESCE` for `fillNullsWith`.
 *
 * Port of `CreateSelectColumnForCombineOutputNode`.
 */
class CreateSelectColumnForCombineOutputNode(
    private val tableAlias: String,
    private val columnResolver: ColumnAssociationResolver,
    private val metricLookup: MetricLookup,
) : InstanceSetTransform<SelectColumnSet> {

    override fun transform(instanceSet: InstanceSet): SelectColumnSet = SelectColumnSet.create(
        metricColumns = createSelectColumnsForMetrics(instanceSet.metricInstances),
        simpleMetricInputColumns = createSelectColumnsForSimpleMetricInputs(instanceSet.simpleMetricInputInstances),
        dimensionColumns = emptyList(),
        timeDimensionColumns = emptyList(),
        entityColumns = emptyList(),
        groupByMetricColumns = emptyList(),
        metadataColumns = emptyList(),
    )

    private fun createSelectColumn(spec: InstanceSpec, fillNullsWith: Int?): SqlSelectColumn {
        val columnName = columnResolver.resolveSpec(spec).columnName
        val columnReferenceExpression = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(tableAlias = tableAlias, columnName = columnName),
            shouldRenderTableAlias = true,
        )
        var selectExpression: SqlExpressionNode =
            SqlFunctionExpression.buildExpressionFromAggregationType(
                aggregationType = AggregationType.MAX,
                sqlColumnExpression = columnReferenceExpression,
                aggParams = null,
            )
        if (fillNullsWith != null) {
            selectExpression = SqlAggregateFunctionExpression.create(
                sqlFunction = SqlFunction.COALESCE,
                sqlFunctionArgs = listOf(
                    selectExpression,
                    SqlStringExpression.create(
                        sqlExpr = fillNullsWith.toString(),
                        bindParameterSet = SqlBindParameterSet.EMPTY,
                        requiresParenthesis = false,
                        usedColumns = null,
                    ),
                ),
            )
        }
        return SqlSelectColumn(expr = selectExpression, columnAlias = columnName)
    }

    private fun createSelectColumnsForMetrics(metricInstances: List<MetricInstance>): List<SqlSelectColumn> {
        return metricInstances.map { metricInstance ->
            val metricReference = MetricReference(elementName = metricInstance.definedFrom.metricName)
            val metric = metricLookup.getMetric(metricReference)
            val fillNullsWith = when (metric.type) {
                MetricType.SIMPLE -> metric.typeParams.fillNullsWith
                MetricType.RATIO,
                MetricType.DERIVED,
                MetricType.CUMULATIVE,
                MetricType.CONVERSION -> null
            }
            createSelectColumn(spec = metricInstance.spec, fillNullsWith = fillNullsWith)
        }
    }

    private fun createSelectColumnsForSimpleMetricInputs(
        instances: List<SimpleMetricInputInstance>,
    ): List<SqlSelectColumn> = instances.map { instance ->
        createSelectColumn(spec = instance.spec, fillNullsWith = instance.spec.fillNullsWith)
    }
}

// =============================================================================================
// ConvertToMetadata / AddMetadata
// =============================================================================================

/**
 * Replace every existing instance with the supplied [MetadataInstance]s, discarding everything
 * else.
 *
 * Port of `ConvertToMetadata`.
 */
class ConvertToMetadata(private val metadataInstances: List<MetadataInstance>) :
    InstanceSetTransform<InstanceSet> {
    override fun transform(instanceSet: InstanceSet): InstanceSet = InstanceSet(
        simpleMetricInputInstances = emptyList(),
        dimensionInstances = emptyList(),
        timeDimensionInstances = emptyList(),
        entityInstances = emptyList(),
        groupByMetricInstances = emptyList(),
        metricInstances = emptyList(),
        metadataInstances = metadataInstances,
    )
}

/** Append the given [metadataInstances] to the set. Port of `AddMetadata`. */
class AddMetadata(private val metadataInstances: List<MetadataInstance>) :
    InstanceSetTransform<InstanceSet> {
    override fun transform(instanceSet: InstanceSet): InstanceSet =
        instanceSet.copy(metadataInstances = instanceSet.metadataInstances + metadataInstances)
}

// =============================================================================================
// ChangeAssociatedColumns
// =============================================================================================

/**
 * Replace each instance's column associations with freshly resolved ones from
 * [columnAssociationResolver]. Useful for pass-through nodes that just rename columns.
 *
 * Port of `ChangeAssociatedColumns`. (Python author flags this class as a candidate for
 * deletion — it's preserved here for parity.)
 */
class ChangeAssociatedColumns(
    private val columnAssociationResolver: ColumnAssociationResolver,
) : InstanceSetTransform<InstanceSet> {

    override fun transform(instanceSet: InstanceSet): InstanceSet = InstanceSet(
        simpleMetricInputInstances = instanceSet.simpleMetricInputInstances.map { x ->
            SimpleMetricInputInstance(
                associatedColumns = listOf(columnAssociationResolver.resolveSpec(x.spec)),
                spec = x.spec,
                definedFrom = x.definedFrom,
                aggregationState = x.aggregationState,
            )
        },
        dimensionInstances = instanceSet.dimensionInstances.map { x ->
            DimensionInstance(
                associatedColumns = listOf(columnAssociationResolver.resolveSpec(x.spec)),
                spec = x.spec,
                definedFrom = x.definedFrom,
            )
        },
        timeDimensionInstances = instanceSet.timeDimensionInstances.map { x ->
            TimeDimensionInstance(
                associatedColumns = listOf(columnAssociationResolver.resolveSpec(x.spec)),
                spec = x.spec,
                definedFrom = x.definedFrom,
            )
        },
        entityInstances = instanceSet.entityInstances.map { x ->
            EntityInstance(
                associatedColumns = listOf(columnAssociationResolver.resolveSpec(x.spec)),
                spec = x.spec,
                definedFrom = x.definedFrom,
            )
        },
        metricInstances = instanceSet.metricInstances.map { x ->
            MetricInstance(
                associatedColumns = listOf(columnAssociationResolver.resolveSpec(x.spec)),
                spec = x.spec,
                definedFrom = x.definedFrom,
            )
        },
        metadataInstances = instanceSet.metadataInstances.map { x ->
            MetadataInstance(
                associatedColumns = listOf(columnAssociationResolver.resolveSpec(x.spec)),
                spec = x.spec,
            )
        },
        groupByMetricInstances = instanceSet.groupByMetricInstances.map { x ->
            GroupByMetricInstance(
                associatedColumns = listOf(columnAssociationResolver.resolveSpec(x.spec)),
                spec = x.spec,
                definedFrom = x.definedFrom,
            )
        },
    )
}
