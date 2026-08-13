package cc.monomer.metricflow.domain.query.filter

import cc.monomer.metricflow.domain.manifest.model.parameterset.DimensionCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.parameterset.EntityCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.parameterset.MetricCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.parameterset.TimeDimensionCallParameterSet
import cc.monomer.metricflow.domain.spec.pattern.DimensionPattern
import cc.monomer.metricflow.domain.spec.pattern.EntityPattern
import cc.monomer.metricflow.domain.spec.pattern.GroupByMetricPattern
import cc.monomer.metricflow.domain.spec.pattern.ParameterSetField
import cc.monomer.metricflow.domain.spec.pattern.SpecPattern
import cc.monomer.metricflow.domain.spec.pattern.SpecPatternParameterSet
import cc.monomer.metricflow.domain.spec.pattern.TimeDimensionPattern

/**
 * Strategy for converting call-parameter sets (extracted from a Jinja
 * template) into [SpecPattern]s that the resolver can match.
 *
 * Port of
 * `metricflow_semantics.query.group_by_item.filter_spec_resolution.filter_pattern_factory.WhereFilterPatternFactory`.
 */
interface WhereFilterPatternFactory {
    fun createForDimension(callParameterSet: DimensionCallParameterSet): SpecPattern
    fun createForTimeDimension(callParameterSet: TimeDimensionCallParameterSet): SpecPattern
    fun createForEntity(callParameterSet: EntityCallParameterSet): SpecPattern
    fun createForMetric(callParameterSet: MetricCallParameterSet): SpecPattern
}

/**
 * Default factory backed by the W7b pattern hierarchy.
 *
 * Port of `DefaultWhereFilterPatternFactory`.
 */
class DefaultWhereFilterPatternFactory : WhereFilterPatternFactory {

    override fun createForDimension(callParameterSet: DimensionCallParameterSet): SpecPattern =
        DimensionPattern(
            parameterSet = SpecPatternParameterSet.fromParameters(
                fieldsToCompare = listOf(
                    ParameterSetField.ELEMENT_NAME,
                    ParameterSetField.ENTITY_LINKS,
                    ParameterSetField.DATE_PART,
                ),
                elementName = callParameterSet.dimensionReference.elementName,
                entityLinks = callParameterSet.entityPath,
                timeGranularityName = null,
                datePart = null,
                metricSubqueryEntityLinks = null,
                descending = callParameterSet.descending,
            ),
            includeTimeDimensions = true,
        )

    override fun createForTimeDimension(callParameterSet: TimeDimensionCallParameterSet): SpecPattern =
        TimeDimensionPattern(
            parameterSet = SpecPatternParameterSet.fromParameters(
                fieldsToCompare = TimeDimensionPattern.getFieldsToCompare(
                    timeGranularityName = callParameterSet.timeGranularityName,
                    datePart = callParameterSet.datePart,
                ),
                elementName = callParameterSet.timeDimensionReference.elementName,
                entityLinks = callParameterSet.entityPath,
                timeGranularityName = callParameterSet.timeGranularityName,
                datePart = callParameterSet.datePart,
                metricSubqueryEntityLinks = null,
                descending = callParameterSet.descending,
            ),
        )

    override fun createForEntity(callParameterSet: EntityCallParameterSet): SpecPattern =
        EntityPattern(
            parameterSet = SpecPatternParameterSet.fromParameters(
                fieldsToCompare = listOf(
                    ParameterSetField.ELEMENT_NAME,
                    ParameterSetField.ENTITY_LINKS,
                ),
                elementName = callParameterSet.entityReference.elementName,
                entityLinks = callParameterSet.entityPath,
                timeGranularityName = null,
                datePart = null,
                metricSubqueryEntityLinks = null,
                descending = callParameterSet.descending,
            ),
        )

    override fun createForMetric(callParameterSet: MetricCallParameterSet): SpecPattern =
        GroupByMetricPattern(
            parameterSet = SpecPatternParameterSet.fromParameters(
                fieldsToCompare = listOf(
                    ParameterSetField.ELEMENT_NAME,
                    ParameterSetField.ENTITY_LINKS,
                ),
                elementName = callParameterSet.metricReference.elementName,
                entityLinks = callParameterSet.groupBy.map {
                    cc.monomer.metricflow.domain.manifest.model.references.EntityReference(it.elementName)
                },
                timeGranularityName = null,
                datePart = null,
                metricSubqueryEntityLinks = null,
                descending = callParameterSet.descending,
            ),
        )
}
