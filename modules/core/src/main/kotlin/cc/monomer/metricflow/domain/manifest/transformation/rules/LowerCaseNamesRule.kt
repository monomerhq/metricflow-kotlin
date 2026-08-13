package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule

/**
 * Lowercases the names of top-level semantic models and their elements (measures, entities,
 * dimensions, and the `defaults.agg_time_dimension`).
 *
 * Port of `metricflow_semantic_interfaces/transformations/names.py::LowerCaseNamesRule`.
 *
 * This is the only primary rule. Many downstream rules rely on case-normalised names —
 * e.g. proxy-metric creation, agg-time-dimension default lookup, custom-granularity matching.
 */
object LowerCaseNamesRule : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val newModels = semanticManifest.semanticModels.map { model ->
            val newMeasures = model.measures.map { it.copy(name = it.name.lowercase()) }
            val newEntities = model.entities.map { it.copy(name = it.name.lowercase()) }
            val newDimensions = model.dimensions.map { it.copy(name = it.name.lowercase()) }
            val newDefaults = model.defaults?.let {
                val agg = it.aggTimeDimension
                if (agg != null) it.copy(aggTimeDimension = agg.lowercase()) else it
            }
            model.copy(
                name = model.name.lowercase(),
                measures = newMeasures,
                entities = newEntities,
                dimensions = newDimensions,
                defaults = newDefaults,
            )
        }
        return semanticManifest.copy(semanticModels = newModels)
    }
}
