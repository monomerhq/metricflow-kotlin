package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.manifest.model.Export
import cc.monomer.metricflow.domain.manifest.model.Metadata
import cc.monomer.metricflow.domain.manifest.model.SavedQueryQueryParams
import cc.monomer.metricflow.domain.manifest.model.SemanticLayerElementConfig
import cc.monomer.metricflow.domain.manifest.model.element.DimensionTypeParams
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.spec.DimensionSpec as SpecDimensionSpec
import cc.monomer.metricflow.domain.manifest.model.element.Dimension as ManifestDimension
import cc.monomer.metricflow.domain.manifest.model.element.Entity as ManifestEntity
import cc.monomer.metricflow.domain.manifest.model.Metric as ManifestMetric
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.SavedQuery as ManifestSavedQuery

/**
 * Public DTOs returned by the [MetricFlowEngine] entry points.
 *
 * Port of `metricflow/engine/models.py` — the four "searchable element" records
 * that the engine surfaces to callers (`Dimension`, `Entity`, `Metric`,
 * `SavedQuery`). These are **separate** from the manifest-side dimension /
 * entity types (`metricflow_semantic_interfaces/implementations/elements/`):
 * the engine variants carry extra context (`entity_links`, `semantic_model_reference`,
 * `dimensions` etc.) that the manifest types intentionally don't store.
 *
 * Mirrors the Python field names and shapes so that the protobuf adapter (see
 * [cc.monomer.metricflow.application.engine.adapter.EngineProtoAdapter])
 * can be a literal field-by-field translator.
 */

/** Marker interface for elements that can be sorted/filtered by a default attribute. */
interface SearchableElement {
    /** The attribute to use when sorting these elements for display. */
    val defaultSearchAndSortAttribute: String
}

/**
 * Engine-facing dimension record. Mirrors `metricflow.engine.models.Dimension`.
 *
 * Carries the resolved `dunder_name` (e.g. `listing__country`) and the chain of
 * `entity_links` that produced it, in addition to the underlying manifest
 * dimension fields. `semanticModelReference` is `null` for the synthetic
 * `metric_time` dimension; for every other dimension it points to the model
 * that defined it.
 */
data class EngineDimension(
    val name: String,
    val dunderName: String,
    val description: String?,
    val type: DimensionType,
    val entityLinks: List<EntityReference>,
    val typeParams: DimensionTypeParams?,
    val metadata: Metadata?,
    val semanticModelReference: SemanticModelReference?,
    val config: SemanticLayerElementConfig?,
    val isPartition: Boolean,
    val expr: String?,
    val label: String?,
) : SearchableElement {
    override val defaultSearchAndSortAttribute: String get() = dunderName

    companion object {
        /** Build from a manifest [ManifestDimension] + the resolved entity-link chain. */
        fun fromManifest(
            dimension: ManifestDimension,
            entityLinks: List<EntityReference>,
            semanticModelReference: SemanticModelReference,
        ): EngineDimension {
            val qualifiedName = SpecDimensionSpec(
                elementName = dimension.name,
                entityLinks = entityLinks,
                alias = null,
            ).dunderName
            return EngineDimension(
                name = dimension.name,
                dunderName = qualifiedName,
                description = dimension.description,
                type = dimension.type,
                typeParams = dimension.typeParams,
                metadata = dimension.metadata,
                config = dimension.config,
                isPartition = dimension.isPartition,
                expr = dimension.expr,
                label = dimension.label,
                entityLinks = entityLinks,
                semanticModelReference = semanticModelReference,
            )
        }
    }
}

/**
 * Engine-facing entity record. Mirrors `metricflow.engine.models.Entity`.
 */
data class EngineEntity(
    val name: String,
    val description: String?,
    val type: EntityType,
    val semanticModelReference: SemanticModelReference,
    val role: String?,
    val config: SemanticLayerElementConfig?,
    val expr: String?,
) : SearchableElement {
    override val defaultSearchAndSortAttribute: String get() = name

    companion object {
        fun fromManifest(
            entity: ManifestEntity,
            semanticModelReference: SemanticModelReference,
        ): EngineEntity = EngineEntity(
            name = entity.name,
            description = entity.description,
            type = entity.type,
            role = entity.role,
            config = entity.config,
            expr = entity.expr,
            semanticModelReference = semanticModelReference,
        )
    }
}

/**
 * Engine-facing metric record. Mirrors `metricflow.engine.models.Metric`.
 */
data class EngineMetric(
    val name: String,
    val description: String?,
    val type: MetricType,
    val typeParams: MetricTypeParams,
    val filter: WhereFilterIntersection?,
    val metadata: Metadata?,
    val dimensions: List<EngineDimension>,
    val label: String?,
    val config: SemanticLayerElementConfig?,
    val semanticModels: List<SemanticModelReference>,
) : SearchableElement {
    override val defaultSearchAndSortAttribute: String get() = name

    companion object {
        fun fromManifest(
            metric: ManifestMetric,
            dimensions: List<EngineDimension>,
            semanticModels: List<SemanticModelReference>,
        ): EngineMetric = EngineMetric(
            name = metric.name,
            description = metric.description,
            type = metric.type,
            typeParams = metric.typeParams,
            filter = metric.filter,
            metadata = metric.metadata,
            dimensions = dimensions,
            label = metric.label,
            config = metric.config,
            semanticModels = semanticModels,
        )
    }
}

/**
 * Engine-facing saved-query record. Mirrors `metricflow.engine.models.SavedQuery`.
 */
data class EngineSavedQuery(
    val name: String,
    val description: String?,
    val label: String?,
    val queryParams: SavedQueryQueryParams,
    val metadata: Metadata?,
    val exports: List<Export>,
    val tags: List<String>,
) : SearchableElement {
    override val defaultSearchAndSortAttribute: String get() = name

    companion object {
        fun fromManifest(savedQuery: ManifestSavedQuery): EngineSavedQuery = EngineSavedQuery(
            name = savedQuery.name,
            description = savedQuery.description,
            label = savedQuery.label,
            queryParams = savedQuery.queryParams,
            metadata = savedQuery.metadata,
            exports = savedQuery.exports,
            tags = savedQuery.tags,
        )
    }
}

/**
 * The `order_by` attribute supported by [MetricFlowEngine.listGroupBys] and
 * [MetricFlowEngine.listDimensions].
 *
 * Mirrors `metricflow.engine.metricflow_engine.GroupByOrderByAttribute`.
 */
enum class GroupByOrderByAttribute(val pythonName: String) {
    DUNDER_NAME("dunder_name"),
    SEMANTIC_MODEL_NAME("semantic_model_name"),
}
