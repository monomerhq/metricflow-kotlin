package cc.monomer.metricflow.domain.spec

/**
 * The pair of linkable specs joined when a conversion metric carries
 * "constant properties".
 *
 * Port of `metricflow_semantics.specs.constant_property_spec.ConstantPropertySpec`.
 *
 * In a conversion-metric join, the base measure's row supplies one spec and
 * the conversion measure supplies the other. Both must match on the join for
 * the conversion to count.
 */
data class ConstantPropertySpec(
    val baseSpec: LinkableInstanceSpec,
    val conversionSpec: LinkableInstanceSpec,
)
