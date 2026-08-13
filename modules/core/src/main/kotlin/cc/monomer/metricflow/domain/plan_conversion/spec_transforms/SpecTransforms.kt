package cc.monomer.metricflow.domain.plan_conversion.spec_transforms

import cc.monomer.metricflow.domain.plan_conversion.helpers.SelectColumnSet
import cc.monomer.metricflow.domain.plan_conversion.helpers.SqlExpressionBuilder
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.InstanceSpecSetTransform
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn

/**
 * Create SELECT columns that coalesce columns corresponding to linkable specs.
 *
 * Port of `metricflow.plan_conversion.spec_transforms.CreateSelectCoalescedColumnsForLinkableSpecs`.
 *
 * Example:
 * ```
 * dimensionSpecs = [DimensionSpec("is_instant")]
 * tableAliases   = ["a", "b"]
 *
 * → COALESCE(a.is_instant, b.is_instant) AS is_instant
 * ```
 *
 * Used by the dataflow→SQL converter when combining multiple sources via FULL OUTER JOINs and
 * the join keys must be coalesced before downstream consumers see them.
 */
class CreateSelectCoalescedColumnsForLinkableSpecs(
    private val columnAssociationResolver: ColumnAssociationResolver,
    private val tableAliases: List<String>,
) : InstanceSpecSetTransform<SelectColumnSet> {

    override fun transform(specSet: InstanceSpecSet): SelectColumnSet {
        val dimensionColumns = mutableListOf<SqlSelectColumn>()
        val timeDimensionColumns = mutableListOf<SqlSelectColumn>()
        val entityColumns = mutableListOf<SqlSelectColumn>()

        for (dimensionSpec in specSet.dimensionSpecs) {
            val columnName = columnAssociationResolver.resolveSpec(dimensionSpec).columnName
            dimensionColumns.add(
                SqlSelectColumn(
                    expr = SqlExpressionBuilder.makeCoalescedExpr(tableAliases, columnName),
                    columnAlias = columnName,
                ),
            )
        }

        for (timeDimensionSpec in specSet.timeDimensionSpecs) {
            val columnName = columnAssociationResolver.resolveSpec(timeDimensionSpec).columnName
            timeDimensionColumns.add(
                SqlSelectColumn(
                    expr = SqlExpressionBuilder.makeCoalescedExpr(tableAliases, columnName),
                    columnAlias = columnName,
                ),
            )
        }

        for (entitySpec in specSet.entitySpecs) {
            val columnName = columnAssociationResolver.resolveSpec(entitySpec).columnName
            entityColumns.add(
                SqlSelectColumn(
                    expr = SqlExpressionBuilder.makeCoalescedExpr(tableAliases, columnName),
                    columnAlias = columnName,
                ),
            )
        }

        return SelectColumnSet.ofLinkable(
            dimensionColumns = dimensionColumns,
            timeDimensionColumns = timeDimensionColumns,
            entityColumns = entityColumns,
        )
    }
}

/**
 * Drop metric and simple-metric-input specs from an [InstanceSpecSet], retaining only the
 * linkable (dimension / time dimension / entity) variants.
 *
 * Port of `metricflow.plan_conversion.spec_transforms.SelectOnlyLinkableSpecs`.
 *
 * Used during dataflow conversion when constructing a projection that should expose only the
 * group-by-side columns of an instance set.
 */
class SelectOnlyLinkableSpecs : InstanceSpecSetTransform<InstanceSpecSet> {
    override fun transform(specSet: InstanceSpecSet): InstanceSpecSet = InstanceSpecSet(
        metricSpecs = emptyList(),
        simpleMetricInputSpecs = emptyList(),
        dimensionSpecs = specSet.dimensionSpecs,
        timeDimensionSpecs = specSet.timeDimensionSpecs,
        entitySpecs = specSet.entitySpecs,
        groupByMetricSpecs = specSet.groupByMetricSpecs,
        metadataSpecs = specSet.metadataSpecs,
    )
}

/**
 * Resolve every spec in the set into a [ColumnAssociation].
 *
 * Port of `metricflow.plan_conversion.spec_transforms.CreateColumnAssociations`. The initial
 * use case (per the upstream KDoc) is figuring out which columns a `WHERE` filter SQL refers to.
 */
class CreateColumnAssociations(
    private val columnAssociationResolver: ColumnAssociationResolver,
) : InstanceSpecSetTransform<List<ColumnAssociation>> {

    override fun transform(specSet: InstanceSpecSet): List<ColumnAssociation> =
        specSet.allSpecs.map { columnAssociationResolver.resolveSpec(it) }
}
