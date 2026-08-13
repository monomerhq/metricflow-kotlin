package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.naming.DUNDER

/**
 * A spec for a column that is built during the dataflow plan and not defined in
 * the manifest.
 *
 * Port of `metricflow_semantics.specs.metadata_spec.MetadataSpec`.
 *
 * Used for synthetic columns (e.g. min/max placeholders, row index columns)
 * that flow through the plan but don't have a manifest definition. The
 * optional [aggType] suffixes the dunder name so multiple variants of the
 * same metadata column can coexist.
 */
data class MetadataSpec(
    override val elementName: String,
    val aggType: AggregationType?,
) : InstanceSpec {

    override val dunderName: String
        get() = if (aggType != null) "$elementName$DUNDER${aggType.value}" else elementName

    override fun <OutputT> accept(visitor: InstanceSpecVisitor<OutputT>): OutputT =
        visitor.visitMetadataSpec(this)
}
