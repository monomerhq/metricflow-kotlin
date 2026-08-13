package cc.monomer.metricflow.domain.semantic_graph.attribute_resolution

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.semantic_graph.trie.DunderNameDescriptor
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.GroupByMetricSpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec

/**
 * Groups a [LinkableInstanceSpec] with extra context.
 *
 * Port of `metricflow_semantics/model/semantics/linkable_element_set_base.py::AnnotatedSpec`.
 *
 * Each annotated spec records:
 *
 * - the resolved spec (returned via [spec]),
 * - the [LinkableElementType] of the element,
 * - the element's properties,
 * - origin and derived-from semantic model names,
 * - inner/outer entity links (for group-by metrics).
 *
 * Two annotated specs that match on `(element_type, element_name, time_grain,
 * date_part, metric_subquery_entity_link_names)` can be combined via [merge] —
 * this is how the resolver merges multiple join paths to the same logical
 * attribute.
 */
data class AnnotatedSpec(
    val elementType: LinkableElementType,
    val elementName: String,
    val entityLinkNames: List<String>,
    val timeGrain: ExpandedTimeGranularity?,
    val datePart: DatePart?,
    val metricSubqueryEntityLinkNames: List<String>,
    val elementProperties: List<GroupByItemProperty>,
    /** The semantic model(s) where the element was defined. */
    val originSemanticModelNames: List<String>,
    val derivedFromSemanticModelNames: List<String>,
) {

    /** Properties as a [FrozenOrderedSet] for fast membership tests. */
    val propertySet: FrozenOrderedSet<GroupByItemProperty>
        get() = FrozenOrderedSet(elementProperties)

    /** Origin semantic-model IDs. */
    val originModelIds: FrozenOrderedSet<SemanticModelId>
        get() = FrozenOrderedSet(originSemanticModelNames.map { SemanticModelId.getInstance(it) })

    /** Bridge to the manifest-side [SemanticModelReference]. */
    val derivedFromSemanticModels: List<SemanticModelReference>
        get() = derivedFromSemanticModelNames.map { SemanticModelReference(it) }

    /** The same as [derivedFromSemanticModels] but from the origin names. */
    val originSemanticModelReferences: List<SemanticModelReference>
        get() = originSemanticModelNames.map { SemanticModelReference(it) }

    /**
     * Resolve to the concrete [LinkableInstanceSpec] type.
     *
     * Branches on [elementType], mirroring Python's `spec` cached_property.
     */
    val spec: LinkableInstanceSpec
        get() {
            val entityLinks = entityLinkNames.map { EntityReference(it) }
            return when (elementType) {
                LinkableElementType.METRIC -> GroupByMetricSpec(
                    elementName = elementName,
                    entityLinks = entityLinks,
                    metricSubqueryEntityLinks = metricSubqueryEntityLinkNames.map { EntityReference(it) },
                    alias = null,
                )
                LinkableElementType.TIME_DIMENSION -> TimeDimensionSpec(
                    elementName = elementName,
                    entityLinks = entityLinks,
                    timeGranularity = timeGrain,
                    datePart = datePart,
                    aggregationState = null,
                    windowFunctions = emptyList(),
                    alias = null,
                )
                LinkableElementType.DIMENSION -> DimensionSpec(
                    elementName = elementName,
                    entityLinks = entityLinks,
                    alias = null,
                )
                LinkableElementType.ENTITY -> EntitySpec(
                    elementName = elementName,
                    entityLinks = entityLinks,
                    alias = null,
                )
            }
        }

    /**
     * Combine two annotated specs that share the same key. Throws if their
     * identifying fields disagree.
     */
    fun merge(other: AnnotatedSpec): AnnotatedSpec {
        if (
            elementType != other.elementType ||
            elementName != other.elementName ||
            timeGrain != other.timeGrain ||
            datePart != other.datePart ||
            metricSubqueryEntityLinkNames != other.metricSubqueryEntityLinkNames
        ) {
            throw RuntimeException(
                "Unable to merge annotated specs due to incompatible fields. " +
                    "self=$this other=$other",
            )
        }

        return AnnotatedSpec(
            elementType = elementType,
            elementName = elementName,
            entityLinkNames = entityLinkNames,
            timeGrain = timeGrain,
            datePart = datePart,
            metricSubqueryEntityLinkNames = metricSubqueryEntityLinkNames,
            elementProperties = (propertySet.union(other.propertySet)).toList(),
            originSemanticModelNames = FrozenOrderedSet(
                originSemanticModelNames + other.originSemanticModelNames,
            ).toList(),
            derivedFromSemanticModelNames = FrozenOrderedSet(
                derivedFromSemanticModelNames + other.derivedFromSemanticModelNames,
            ).toList(),
        )
    }

    companion object {
        /** Construct an [AnnotatedSpec] from typed inputs. Mirrors Python's `create`. */
        fun create(
            elementType: LinkableElementType,
            elementName: String,
            properties: Iterable<GroupByItemProperty>,
            originModelIds: Iterable<SemanticModelId>,
            derivedFromSemanticModels: Iterable<SemanticModelReference>,
            entityLinks: List<EntityReference>,
            metricSubqueryEntityLinks: List<EntityReference>?,
            timeGrain: ExpandedTimeGranularity?,
            datePart: DatePart?,
        ): AnnotatedSpec {
            val entityLinkNames = entityLinks.map { it.elementName }
            val elementProperties = FrozenOrderedSet(properties).toList()
            val originModelNames = FrozenOrderedSet(originModelIds.map { it.modelName }).toList()
            val derivedFromNames = FrozenOrderedSet(
                derivedFromSemanticModels.map { it.semanticModelName },
            ).toList()
            val metricSubqueryEntityLinkNames =
                metricSubqueryEntityLinks?.map { it.elementName } ?: emptyList()

            return AnnotatedSpec(
                elementType = elementType,
                elementName = elementName,
                entityLinkNames = entityLinkNames,
                elementProperties = elementProperties,
                timeGrain = timeGrain,
                datePart = datePart,
                metricSubqueryEntityLinkNames = metricSubqueryEntityLinkNames,
                originSemanticModelNames = originModelNames,
                derivedFromSemanticModelNames = derivedFromNames,
            )
        }

        /**
         * Build a sequence of annotated specs from a [DunderNameDescriptor] + the
         * dunder-name path that reached it.
         *
         * Port of Python's `create_from_indexed_dunder_name`. Metrics produce one
         * annotated spec per entity-key query option (group-by metrics fan out
         * over multiple possible subquery entity-link paths); other element types
         * produce exactly one annotated spec.
         */
        fun createFromIndexedDunderName(
            indexedDunderName: IndexedDunderName,
            descriptor: DunderNameDescriptor,
        ): List<AnnotatedSpec> {
            val items = mutableListOf<AnnotatedSpec>()
            val elementType = descriptor.elementType

            // Compute the element name + entity link list given the element type.
            val (elementName, entityLinks) = when (elementType) {
                LinkableElementType.DIMENSION,
                LinkableElementType.ENTITY,
                LinkableElementType.METRIC,
                -> indexedDunderName.last() to indexedDunderName.dropLast(1).map { EntityReference(it) }
                LinkableElementType.TIME_DIMENSION -> {
                    val name = if (indexedDunderName.size >= 2) {
                        indexedDunderName[indexedDunderName.size - 2]
                    } else {
                        indexedDunderName.last()
                    }
                    val links = if (indexedDunderName.size >= 2) {
                        indexedDunderName.dropLast(2).map { EntityReference(it) }
                    } else {
                        emptyList()
                    }
                    name to links
                }
            }

            when (elementType) {
                LinkableElementType.DIMENSION,
                LinkableElementType.ENTITY,
                LinkableElementType.TIME_DIMENSION,
                -> items.add(
                    create(
                        elementType = descriptor.elementType,
                        elementName = elementName,
                        properties = descriptor.elementProperties,
                        originModelIds = descriptor.originModelIds,
                        derivedFromSemanticModels = descriptor.derivedFromModelIds.map {
                            it.semanticModelReference
                        },
                        entityLinks = entityLinks,
                        metricSubqueryEntityLinks = null,
                        timeGrain = descriptor.timeGrain,
                        datePart = descriptor.datePart,
                    ),
                )
                LinkableElementType.METRIC ->
                    for (entityKeyQuery in descriptor.entityKeyQueriesForGroupByMetric) {
                        items.add(
                            create(
                                elementType = descriptor.elementType,
                                elementName = elementName,
                                properties = descriptor.elementProperties,
                                originModelIds = descriptor.originModelIds,
                                derivedFromSemanticModels = FrozenOrderedSet(
                                    descriptor.derivedFromModelIds.map { it.semanticModelReference } +
                                        entityKeyQuery.derivedFromModelIds.map { it.semanticModelReference },
                                ).toList(),
                                entityLinks = entityLinks,
                                metricSubqueryEntityLinks = entityKeyQuery.entityKeyQuery.map {
                                    EntityReference(it)
                                },
                                timeGrain = null,
                                datePart = null,
                            ),
                        )
                    }
            }

            return items
        }
    }
}
