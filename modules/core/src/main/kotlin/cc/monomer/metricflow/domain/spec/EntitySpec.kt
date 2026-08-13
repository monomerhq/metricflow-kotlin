package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.domain.manifest.model.references.EntityReference

/**
 * A spec for an entity column.
 *
 * Port of `metricflow_semantics.specs.entity_spec.EntitySpec`.
 *
 * Entities are the join keys that connect semantic models; an `EntitySpec`
 * names the entity column at a specific point in a join path (the
 * [entityLinks] chain).
 */
data class EntitySpec(
    override val elementName: String,
    override val entityLinks: List<EntityReference>,
    override val alias: String?,
) : LinkableInstanceSpec {

    override val reference: EntityReference
        get() = EntityReference(elementName)

    override fun withoutFirstEntityLink(): EntitySpec {
        check(entityLinks.isNotEmpty()) { "Spec does not have any entity links: $this" }
        return EntitySpec(elementName = elementName, entityLinks = entityLinks.drop(1), alias = null)
    }

    override fun withoutEntityLinks(): EntitySpec =
        EntitySpec(elementName = elementName, entityLinks = emptyList(), alias = null)

    /**
     * Creates a tuple of linkless entities that could be included in the
     * `entityLinks` of another spec.
     *
     * For example, used as a prefix to a [DimensionSpec]'s entity links when
     * a join is occurring via this entity.
     */
    val asLinklessPrefix: List<EntityReference>
        get() = listOf(EntityReference(elementName)) + entityLinks

    override fun withEntityPrefix(entityPrefix: EntityReference): EntitySpec =
        EntitySpec(
            elementName = elementName,
            entityLinks = listOf(entityPrefix) + entityLinks,
            alias = alias,
        )

    override fun withAlias(alias: String?): EntitySpec = copy(alias = alias)

    override fun <OutputT> accept(visitor: InstanceSpecVisitor<OutputT>): OutputT =
        visitor.visitEntitySpec(this)

    companion object {
        /** Construct a bare [EntitySpec] with no entity links and no alias. */
        fun fromElementName(elementName: String): EntitySpec =
            EntitySpec(elementName = elementName, entityLinks = emptyList(), alias = null)

        /** Construct an [EntitySpec] from an [EntityReference]. */
        fun fromReference(entityReference: EntityReference): EntitySpec =
            EntitySpec(elementName = entityReference.elementName, entityLinks = emptyList(), alias = null)
    }
}
