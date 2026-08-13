package cc.monomer.metricflow.domain.query.parameter

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.query.input.ResolverInputForGroupByItem
import cc.monomer.metricflow.domain.query.input.ResolverInputForMetric
import cc.monomer.metricflow.domain.query.input.ResolverInputForOrderByItem
import cc.monomer.metricflow.domain.query.naming.MetricNamingScheme
import cc.monomer.metricflow.domain.query.naming.ObjectBuilderNamingScheme
import cc.monomer.metricflow.domain.query.naming.QueryItemLocation
import cc.monomer.metricflow.domain.spec.naming.StructuredLinkableSpecName
import cc.monomer.metricflow.domain.spec.pattern.EntityLinkPattern
import cc.monomer.metricflow.domain.spec.pattern.ParameterSetField
import cc.monomer.metricflow.domain.spec.pattern.SpecPatternParameterSet
import cc.monomer.metricflow.domain.spec.pattern.TimeDimensionPattern

/**
 * Concrete query-parameter dataclasses.
 *
 * Port of `metricflow_semantics.specs.query_param_implementations.*`.
 *
 * The Python module pairs each protocol with a frozen dataclass; the
 * Kotlin port keeps the same factory shape. Each parameter exposes
 * [queryResolverInput] which converts itself into the matching
 * `ResolverInputFor*` record.
 */

/** A time-dimension query parameter. Port of `TimeDimensionParameter`. */
data class TimeDimensionParameter(
    override val name: String,
    override val grain: String?,
    override val datePart: DatePart?,
    override val alias: String?,
) : TimeDimensionQueryParameter {

    override fun queryResolverInput(semanticManifestLookup: SemanticManifestLookup): ResolverInputForGroupByItem {
        val customGrainNames = semanticManifestLookup
            .semanticModelLookup
            .customGranularityNames
        val structured = StructuredLinkableSpecName.fromName(
            qualifiedName = name.lowercase(),
            customGranularityNames = customGrainNames,
        )
        return ResolverInputForGroupByItem(
            inputObj = this,
            inputObjNamingScheme = ObjectBuilderNamingScheme(),
            specPattern = EntityLinkPattern(
                parameterSet = SpecPatternParameterSet.fromParameters(
                    fieldsToCompare = TimeDimensionPattern.getFieldsToCompare(
                        timeGranularityName = grain,
                        datePart = datePart,
                    ),
                    elementName = structured.elementName,
                    entityLinks = structured.entityLinkNames.map { EntityReference(it) },
                    timeGranularityName = grain,
                    datePart = datePart,
                    metricSubqueryEntityLinks = null,
                    descending = null,
                ),
            ),
            alias = alias,
        )
    }

    /** Return a new copy with the alias replaced. */
    fun withAlias(alias: String?): TimeDimensionParameter = copy(alias = alias)
}

/**
 * A dimension or entity query parameter — the resolver disambiguates
 * which once the manifest is consulted.
 *
 * Port of `DimensionOrEntityParameter`.
 */
data class DimensionOrEntityParameter(
    override val name: String,
    override val alias: String?,
) : DimensionOrEntityQueryParameter {

    override fun queryResolverInput(semanticManifestLookup: SemanticManifestLookup): ResolverInputForGroupByItem {
        val customGrainNames = semanticManifestLookup
            .semanticModelLookup
            .customGranularityNames
        val structured = StructuredLinkableSpecName.fromName(
            qualifiedName = name.lowercase(),
            customGranularityNames = customGrainNames,
        )
        return ResolverInputForGroupByItem(
            inputObj = this,
            inputObjNamingScheme = ObjectBuilderNamingScheme(),
            specPattern = EntityLinkPattern(
                parameterSet = SpecPatternParameterSet.fromParameters(
                    fieldsToCompare = listOf(
                        ParameterSetField.ELEMENT_NAME,
                        ParameterSetField.ENTITY_LINKS,
                        ParameterSetField.DATE_PART,
                    ),
                    elementName = structured.elementName,
                    entityLinks = structured.entityLinkNames.map { EntityReference(it) },
                    timeGranularityName = null,
                    datePart = null,
                    metricSubqueryEntityLinks = null,
                    descending = null,
                ),
            ),
            alias = alias,
        )
    }

    fun withAlias(alias: String?): DimensionOrEntityParameter = copy(alias = alias)
}

/** A metric query parameter. Port of `MetricParameter`. */
data class MetricParameter(
    override val name: String,
    override val alias: String?,
) : MetricQueryParameter {

    override fun queryResolverInput(semanticManifestLookup: SemanticManifestLookup): ResolverInputForMetric {
        val namingScheme = MetricNamingScheme()
        return ResolverInputForMetric(
            inputObj = this,
            namingScheme = namingScheme,
            specPattern = namingScheme.specPattern(
                inputStr = name,
                semanticManifestLookup = semanticManifestLookup,
                queryItemLocation = QueryItemLocation.NON_ORDER_BY,
            ),
            alias = alias,
        )
    }

    fun withAlias(alias: String?): MetricParameter = copy(alias = alias)
}

/**
 * An order-by query parameter.
 *
 * Port of `OrderByParameter`.
 *
 * The Python typealias `InputOrderByParameter` allows order-by to
 * reference any of [MetricParameter], [DimensionOrEntityParameter], or
 * [TimeDimensionParameter] — we accept the same surface via [orderBy]
 * typed as `Any` and validate at construction.
 */
data class OrderByParameter(
    override val orderBy: Any,
    override val descending: Boolean,
) : OrderByQueryParameter {

    init {
        require(
            orderBy is MetricParameter ||
                orderBy is DimensionOrEntityParameter ||
                orderBy is TimeDimensionParameter,
        ) { "orderBy must be a MetricParameter / DimensionOrEntityParameter / TimeDimensionParameter; got $orderBy" }
    }

    override fun queryResolverInput(semanticManifestLookup: SemanticManifestLookup): ResolverInputForOrderByItem {
        val possible = when (val p = orderBy) {
            is MetricParameter -> p.queryResolverInput(semanticManifestLookup)
            is DimensionOrEntityParameter -> p.queryResolverInput(semanticManifestLookup)
            is TimeDimensionParameter -> p.queryResolverInput(semanticManifestLookup)
            else -> error("unreachable")
        }
        return ResolverInputForOrderByItem(
            inputObj = this,
            possibleInputs = listOf(possible),
            descending = descending,
        )
    }

    /** Return a new copy with the inner parameter's alias replaced. */
    fun withAlias(alias: String?): OrderByParameter = OrderByParameter(
        orderBy = when (val p = orderBy) {
            is MetricParameter -> p.withAlias(alias)
            is DimensionOrEntityParameter -> p.withAlias(alias)
            is TimeDimensionParameter -> p.withAlias(alias)
            else -> error("unreachable")
        },
        descending = descending,
    )
}

/** A reference to a saved query by name. Port of `SavedQueryParameter`. */
data class SavedQueryParameterImpl(
    override val name: String,
) : SavedQueryParameter
