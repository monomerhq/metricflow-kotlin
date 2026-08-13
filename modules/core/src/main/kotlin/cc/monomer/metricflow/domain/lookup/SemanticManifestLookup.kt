package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.common.time.TimeSpineSource
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity

/**
 * Composition root that provides convenient lookup methods to get semantic attributes for a manifest.
 *
 * Port of `metricflow_semantics/model/semantic_manifest_lookup.py::SemanticManifestLookup`.
 *
 * Owns three primary lookups:
 *
 * - [semanticModelLookup] — model index (name-to-model, dimension/entity reverse indexes).
 * - [metricLookup] — metric index.
 * - [timeSpineSources] / [customGranularities] — derived time-spine metadata from
 *   [TimeSpineSource.buildStandardTimeSpineSources] + [TimeSpineSource.buildCustomGranularities].
 *
 * ### Scope note
 *
 * Python's `SemanticManifestLookup` also wires in [ManifestObjectLookup], [SemanticGraphBuilder],
 * the `MetricFlowPathfinder`, and `SemanticGraphGroupByItemSetResolver`. All four belong to
 * `:domain:semantic_graph` (W7b) and are not yet ported. The W7a port is intentionally limited
 * to the manifest-level indexes that don't transitively require the semantic graph. W7b will
 * extend this class to assemble the resolver wiring described in the Python source.
 */
class SemanticManifestLookup(
    val semanticManifest: SemanticManifest,
) {

    /**
     * Map of grain to time spine source, built from the manifest's `project_configuration.time_spines`
     * (and the legacy `time_spine_table_configurations` for backwards compatibility).
     */
    val timeSpineSources: Map<TimeGranularity, TimeSpineSource> =
        TimeSpineSource.buildStandardTimeSpineSources(semanticManifest)

    /** Mapping of custom-grain name to lifted [ExpandedTimeGranularity]. */
    val customGranularities: Map<String, ExpandedTimeGranularity> =
        TimeSpineSource.buildCustomGranularities(timeSpineSources.values)

    /** Model index — see [SemanticModelLookup]. */
    val semanticModelLookup: SemanticModelLookup = SemanticModelLookup(
        semanticManifest = semanticManifest,
        customGranularities = customGranularities,
    )

    /** Metric index — see [MetricLookup]. */
    val metricLookup: MetricLookup = MetricLookup(semanticManifest = semanticManifest)
}
