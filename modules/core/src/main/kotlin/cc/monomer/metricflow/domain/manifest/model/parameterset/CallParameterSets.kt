package cc.monomer.metricflow.domain.manifest.model.parameterset

import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.LinkableElementReference
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference

/**
 * Parameters surfaced when a `Dimension(...)`, `TimeDimension(...)`, `Entity(...)`,
 * or `Metric(...)` template call is parsed from a where-filter Jinja template.
 *
 * Port of `metricflow_semantic_interfaces/call_parameter_sets.py`.
 *
 * These are pure runtime data types — never serialised to a manifest — so they
 * are plain data classes without `@Serializable`.
 */

/** When `Dimension(...)` is used in a Jinja template, the parameters to that call. */
data class DimensionCallParameterSet(
    val entityPath: List<EntityReference>,
    val dimensionReference: DimensionReference,
    val descending: Boolean?,
)

/** When `TimeDimension(...)` is used in a Jinja template, the parameters to that call. */
data class TimeDimensionCallParameterSet(
    val entityPath: List<EntityReference>,
    val timeDimensionReference: TimeDimensionReference,
    val timeGranularityName: String?,
    val datePart: DatePart?,
    val descending: Boolean?,
)

/** When `Entity(...)` is used in a Jinja template, the parameters to that call. */
data class EntityCallParameterSet(
    val entityPath: List<EntityReference>,
    val entityReference: EntityReference,
    val descending: Boolean?,
)

/** When `Metric(...)` is used in a Jinja template, the parameters to that call. */
data class MetricCallParameterSet(
    val metricReference: MetricReference,
    val groupBy: List<LinkableElementReference>,
    val descending: Boolean?,
)

/** The calls for metric items made in the Jinja template of the where filter. */
data class JinjaCallParameterSets(
    val dimensionCallParameterSets: List<DimensionCallParameterSet>,
    val timeDimensionCallParameterSets: List<TimeDimensionCallParameterSet>,
    val entityCallParameterSets: List<EntityCallParameterSet>,
    val metricCallParameterSets: List<MetricCallParameterSet>,
)

/** Raised when a Jinja call expression in a where-filter cannot be parsed. */
class ParseJinjaObjectException(message: String) : RuntimeException(message)
