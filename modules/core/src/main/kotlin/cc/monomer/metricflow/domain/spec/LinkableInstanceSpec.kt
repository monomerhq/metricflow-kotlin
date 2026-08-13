package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.LinkableElementReference
import cc.monomer.metricflow.domain.spec.naming.StructuredLinkableSpecName

/**
 * A spec for a column that participates in joins — has an [entityLinks] chain.
 *
 * Port of `metricflow_semantics.specs.instance_spec.LinkableInstanceSpec`.
 *
 * Concrete variants: [DimensionSpec], [TimeDimensionSpec], [EntitySpec],
 * [GroupByMetricSpec].
 *
 * The `entityLinks` chain represents the join path used to reach this element
 * from a measure's source semantic model. For example,
 * `user_id__device_id__platform` describes a `platform` dimension reached by
 * first joining on `user_id` then on `device_id`.
 *
 * The [alias] is the optional name to project this column under in the output
 * (used by callers that pass the spec into a `SELECT … AS alias`).
 */
sealed interface LinkableInstanceSpec : InstanceSpec {
    /** A list representing the join path of entities to get to this element. */
    val entityLinks: List<EntityReference>

    /** Optional output alias. `null` keeps the canonical dunder name. */
    val alias: String?

    /** The [LinkableElementReference] associated with this spec. */
    val reference: LinkableElementReference

    /** Return this spec with the first entity link removed. */
    fun withoutFirstEntityLink(): LinkableInstanceSpec

    /** Return this spec with all entity links removed. */
    fun withoutEntityLinks(): LinkableInstanceSpec

    /** Add the supplied entity prefix to the start of the entity links. */
    fun withEntityPrefix(entityPrefix: EntityReference): LinkableInstanceSpec

    /** Return the same instance spec with the alias field replaced. */
    override fun withAlias(alias: String?): LinkableInstanceSpec

    override val dunderName: String
        get() = StructuredLinkableSpecName(
            entityLinkNames = entityLinks.map { it.elementName },
            elementName = elementName,
            timeGranularityName = null,
            datePart = null,
            metricSubqueryEntityLinkNames = null,
        ).dunderName

    companion object {
        /** Merge all linkable spec lists into a single list, preserving order. */
        fun mergeLinkableSpecs(vararg specs: Iterable<LinkableInstanceSpec>): List<LinkableInstanceSpec> {
            val result = mutableListOf<LinkableInstanceSpec>()
            for (s in specs) result.addAll(s)
            return result
        }
    }
}
