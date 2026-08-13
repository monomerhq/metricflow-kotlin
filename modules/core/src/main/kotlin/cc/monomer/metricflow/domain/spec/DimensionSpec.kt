package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference

/**
 * A spec for a categorical dimension column.
 *
 * Port of `metricflow_semantics.specs.dimension_spec.DimensionSpec`.
 *
 * Note that time dimensions have their own [TimeDimensionSpec] type even
 * though Python derives `TimeDimensionSpec` from `DimensionSpec`. In Kotlin we
 * keep the two as sibling variants of [LinkableInstanceSpec] (data classes
 * cannot inherit), which is why [TimeDimensionSpec.dimensionReference] exists
 * to recover the underlying dimension reference.
 */
data class DimensionSpec(
    override val elementName: String,
    override val entityLinks: List<EntityReference>,
    override val alias: String?,
) : LinkableInstanceSpec {

    override val reference: DimensionReference
        get() = DimensionReference(elementName)

    override fun withoutFirstEntityLink(): DimensionSpec {
        check(entityLinks.isNotEmpty()) { "Spec does not have any entity links: $this" }
        return DimensionSpec(
            elementName = elementName,
            entityLinks = entityLinks.drop(1),
            alias = null,
        )
    }

    override fun withoutEntityLinks(): DimensionSpec =
        DimensionSpec(elementName = elementName, entityLinks = emptyList(), alias = null)

    override fun withEntityPrefix(entityPrefix: EntityReference): DimensionSpec =
        DimensionSpec(
            elementName = elementName,
            entityLinks = listOf(entityPrefix) + entityLinks,
            alias = alias,
        )

    override fun withAlias(alias: String?): DimensionSpec = copy(alias = alias)

    override fun <OutputT> accept(visitor: InstanceSpecVisitor<OutputT>): OutputT =
        visitor.visitDimensionSpec(this)

    companion object {
        /** Construct a bare [DimensionSpec] with no entity links and no alias. */
        fun fromElementName(elementName: String): DimensionSpec =
            DimensionSpec(elementName = elementName, entityLinks = emptyList(), alias = null)

        /** Adapter: build a [DimensionSpec] from any [LinkableInstanceSpec]. */
        fun fromLinkable(spec: LinkableInstanceSpec): DimensionSpec =
            DimensionSpec(elementName = spec.elementName, entityLinks = spec.entityLinks, alias = null)
    }
}
