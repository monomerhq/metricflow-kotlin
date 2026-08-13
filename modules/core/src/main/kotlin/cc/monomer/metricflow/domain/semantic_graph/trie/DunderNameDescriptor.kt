package cc.monomer.metricflow.domain.semantic_graph.trie

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.IndexedDunderName

/**
 * A possible entity-key query for a group-by metric.
 *
 * Port of `EntityKeyQueryForGroupByMetric` in
 * `metricflow_semantics/semantic_graph/trie_resolver/dunder_name_descriptor.py`.
 *
 * E.g. for the `bookings_source` semantic model, `booking__listing` is a
 * possible entity-key query.
 */
data class EntityKeyQueryForGroupByMetric(
    val entityKeyQuery: IndexedDunderName,
    val derivedFromModelIds: List<SemanticModelId>,
)

/**
 * A descriptor stored at a node in a [DunderNameTrie].
 *
 * Port of `metricflow_semantics/semantic_graph/trie_resolver/dunder_name_descriptor.py::DunderNameDescriptor`.
 *
 * The fields parallel those on
 * [cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec],
 * but the descriptor is what lives in the trie before annotated specs are
 * materialised. Two descriptors that share the immutable identifier fields
 * (`element_type`, `time_grain`, `date_part`,
 * `entity_key_queries_for_group_by_metric`) can be combined via [merge].
 */
data class DunderNameDescriptor(
    val elementType: LinkableElementType,
    val timeGrain: ExpandedTimeGranularity?,
    val datePart: DatePart?,
    val elementProperties: List<GroupByItemProperty>,
    val originModelIds: List<SemanticModelId>,
    val derivedFromModelIds: List<SemanticModelId>,
    val entityKeyQueriesForGroupByMetric: List<EntityKeyQueryForGroupByMetric>,
) {

    /** Merge `other` into this descriptor. */
    fun merge(other: DunderNameDescriptor): DunderNameDescriptor = DunderNameDescriptor(
        elementType = elementType,
        timeGrain = timeGrain,
        datePart = datePart,
        elementProperties = elementProperties + other.elementProperties,
        originModelIds = originModelIds + other.originModelIds,
        derivedFromModelIds = derivedFromModelIds + other.derivedFromModelIds,
        entityKeyQueriesForGroupByMetric = entityKeyQueriesForGroupByMetric,
    )

    /** Return a copy with additional `derivedFromModelIds`. */
    fun mergeDerivedFromModelIds(extra: List<SemanticModelId>): DunderNameDescriptor =
        copy(derivedFromModelIds = derivedFromModelIds + extra)

    /** Return `true` iff the two descriptors agree on their identifying fields. */
    fun isMergeable(other: DunderNameDescriptor): Boolean =
        elementType == other.elementType &&
            timeGrain == other.timeGrain &&
            datePart == other.datePart &&
            entityKeyQueriesForGroupByMetric == other.entityKeyQueriesForGroupByMetric
}
