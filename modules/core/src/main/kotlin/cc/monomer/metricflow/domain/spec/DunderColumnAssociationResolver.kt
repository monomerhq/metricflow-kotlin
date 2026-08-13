package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.domain.manifest.model.naming.DUNDER

/**
 * Default [ColumnAssociationResolver] that names every column with a
 * double-underscore separator.
 *
 * Port of
 * `metricflow_semantics.specs.dunder_column_association_resolver.DunderColumnAssociationResolver`.
 *
 * Examples:
 * - `DimensionSpec(elementName="country", entityLinks=["listing"])` → `listing__country`
 * - `TimeDimensionSpec(elementName="ds", entityLinks=[], timeGranularity=MONTH)` → `ds__month`
 *
 * The single Boolean parameter [dunderPrefixSimpleMetricInputs] controls
 * whether simple-metric inputs receive a leading `__` prefix. Python's
 * default is `True`; Kotlin callers must be explicit (CLAUDE.md "no default
 * parameters" exception is invoked only at API edges, not interior services).
 */
class DunderColumnAssociationResolver(
    private val dunderPrefixSimpleMetricInputs: Boolean,
) : ColumnAssociationResolver {

    private val visitor = DunderVisitor(dunderPrefixSimpleMetricInputs)

    override fun resolveSpec(spec: InstanceSpec): ColumnAssociation = spec.accept(visitor)

    override fun withOptions(dunderPrefixSimpleMetricInputs: Boolean): DunderColumnAssociationResolver =
        DunderColumnAssociationResolver(dunderPrefixSimpleMetricInputs)

    private class DunderVisitor(
        private val dunderPrefixSimpleMetricInputs: Boolean,
    ) : InstanceSpecVisitor<ColumnAssociation> {

        override fun visitMetricSpec(spec: MetricSpec): ColumnAssociation =
            ColumnAssociation.ofSingle(spec.alias ?: spec.elementName)

        override fun visitSimpleMetricInputSpec(spec: SimpleMetricInputSpec): ColumnAssociation =
            if (dunderPrefixSimpleMetricInputs) {
                ColumnAssociation.ofSingle("$DUNDER${spec.elementName}")
            } else {
                ColumnAssociation.ofSingle(spec.elementName)
            }

        override fun visitDimensionSpec(spec: DimensionSpec): ColumnAssociation =
            ColumnAssociation.ofSingle(spec.alias ?: spec.dunderName)

        override fun visitTimeDimensionSpec(spec: TimeDimensionSpec): ColumnAssociation {
            val name = spec.alias ?: buildString {
                append(spec.dunderName)
                if (spec.aggregationState != null) {
                    append(DUNDER)
                    append(spec.aggregationState.value.lowercase())
                }
                if (spec.windowFunctions.isNotEmpty()) {
                    append(DUNDER)
                    append(spec.windowFunctions.joinToString(DUNDER) { it.sql.lowercase() })
                }
            }
            return ColumnAssociation.ofSingle(name)
        }

        override fun visitEntitySpec(spec: EntitySpec): ColumnAssociation =
            ColumnAssociation.ofSingle(spec.alias ?: spec.dunderName)

        override fun visitGroupByMetricSpec(spec: GroupByMetricSpec): ColumnAssociation =
            ColumnAssociation.ofSingle(spec.dunderName)

        override fun visitMetadataSpec(spec: MetadataSpec): ColumnAssociation =
            ColumnAssociation.ofSingle(spec.dunderName)
    }
}
