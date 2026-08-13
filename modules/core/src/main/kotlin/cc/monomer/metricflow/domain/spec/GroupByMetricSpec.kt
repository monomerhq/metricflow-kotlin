package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.GroupByMetricReference
import cc.monomer.metricflow.domain.spec.naming.StructuredLinkableSpecName

/**
 * A spec for a metric used as a group-by item.
 *
 * Port of `metricflow_semantics.specs.group_by_metric_spec.GroupByMetricSpec`.
 *
 * A group-by metric ("Metric as a dimension") joins the outer query against a
 * subquery that aggregates the inner metric. Two entity paths are needed:
 *
 * - [entityLinks] — the outer query's join path to reach the subquery.
 * - [metricSubqueryEntityLinks] — the subquery's own join path used to group
 *   the inner metric to a join key. The last entity in this list is the join
 *   key shared between subquery and outer query, so it must equal the last
 *   element of [entityLinks] (when the outer query is past the join hop).
 *
 * `__post_init__` invariants are reproduced in `init`.
 */
data class GroupByMetricSpec(
    override val elementName: String,
    override val entityLinks: List<EntityReference>,
    val metricSubqueryEntityLinks: List<EntityReference>,
    override val alias: String?,
) : LinkableInstanceSpec {

    init {
        check(metricSubqueryEntityLinks.isNotEmpty()) {
            "GroupByMetricSpec must have at least one metric_subquery_entity_link."
        }
        if (entityLinks.isNotEmpty()) {
            check(metricSubqueryEntityLinks.last() == entityLinks.last()) {
                "Inner and outer query must have the same last entity link in order to join on that link."
            }
        }
    }

    override val reference: GroupByMetricReference
        get() = GroupByMetricReference(elementName)

    override val dunderName: String
        get() = StructuredLinkableSpecName(
            entityLinkNames = entityLinks.map { it.elementName },
            elementName = elementName,
            timeGranularityName = null,
            datePart = null,
            metricSubqueryEntityLinkNames = metricSubqueryEntityLinks.map { it.elementName },
        ).dunderName

    /** The last entity in the outer query — the join key for the subquery. */
    val lastEntityLink: EntityReference
        get() {
            check(entityLinks.isNotEmpty()) { "Spec does not have any entity links: $this" }
            return entityLinks.last()
        }

    /** Spec for the entity that the metric is grouped by inside the subquery. */
    val metricSubqueryEntitySpec: EntitySpec
        get() = EntitySpec(
            elementName = metricSubqueryEntityLinks.last().elementName,
            entityLinks = metricSubqueryEntityLinks.dropLast(1),
            alias = null,
        )

    override fun withoutFirstEntityLink(): GroupByMetricSpec {
        check(entityLinks.isNotEmpty()) { "Spec does not have any entity links: $this" }
        return GroupByMetricSpec(
            elementName = elementName,
            entityLinks = entityLinks.drop(1),
            metricSubqueryEntityLinks = metricSubqueryEntityLinks,
            alias = null,
        )
    }

    override fun withoutEntityLinks(): GroupByMetricSpec =
        GroupByMetricSpec(
            elementName = elementName,
            entityLinks = emptyList(),
            metricSubqueryEntityLinks = metricSubqueryEntityLinks,
            alias = null,
        )

    override fun withEntityPrefix(entityPrefix: EntityReference): GroupByMetricSpec =
        GroupByMetricSpec(
            elementName = elementName,
            entityLinks = listOf(entityPrefix) + entityLinks,
            metricSubqueryEntityLinks = metricSubqueryEntityLinks,
            alias = alias,
        )

    override fun withAlias(alias: String?): GroupByMetricSpec = copy(alias = alias)

    /**
     * Equality intentionally ignores [metricSubqueryEntityLinks] and [alias]
     * — Python's `__eq__` compares only `element_name` + `entity_links`.
     */
    override fun equals(other: Any?): Boolean {
        if (other !is GroupByMetricSpec) return false
        return elementName == other.elementName && entityLinks == other.entityLinks
    }

    /**
     * Hash also includes [metricSubqueryEntityLinks] (matches Python's
     * `__hash__`). Two specs that are `equal` may have different hash codes,
     * which is technically a contract violation; this mirrors Python verbatim
     * — downstream code relies on the subquery link being significant for
     * grouping but not for identity comparison.
     */
    override fun hashCode(): Int {
        var h = elementName.hashCode()
        h = 31 * h + entityLinks.hashCode()
        h = 31 * h + metricSubqueryEntityLinks.hashCode()
        return h
    }

    override fun <OutputT> accept(visitor: InstanceSpecVisitor<OutputT>): OutputT =
        visitor.visitGroupByMetricSpec(this)
}
