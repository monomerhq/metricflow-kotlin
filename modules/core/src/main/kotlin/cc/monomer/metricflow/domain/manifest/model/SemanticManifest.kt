package cc.monomer.metricflow.domain.manifest.model

import cc.monomer.metricflow.domain.manifest.model.element.Measure
import kotlinx.serialization.Serializable

/**
 * Top-level manifest: holds everything the semantic layer needs to render a query.
 *
 * Port of `metricflow_semantic_interfaces/implementations/semantic_manifest.py::PydanticSemanticManifest`.
 *
 * A manifest is the "compiled" semantic model: semantic models (data sources + measures /
 * dimensions / entities), the metrics defined over them, project-wide configuration (including
 * time spines), and saved queries.
 */
@Serializable
data class SemanticManifest(
    val semanticModels: List<SemanticModel>,
    val metrics: List<Metric>,
    val projectConfiguration: ProjectConfiguration,
    val savedQueries: List<SavedQuery> = emptyList(),
) {
    /** Index from measure name to the `(semanticModel, measure)` pair that defines it. */
    fun buildMeasureNameToModelAndMeasureMap(): Map<String, Pair<SemanticModel, Measure>> {
        val map = mutableMapOf<String, Pair<SemanticModel, Measure>>()
        for (model in semanticModels) {
            for (measure in model.measures) {
                map[measure.name] = model to measure
            }
        }
        return map
    }
}
