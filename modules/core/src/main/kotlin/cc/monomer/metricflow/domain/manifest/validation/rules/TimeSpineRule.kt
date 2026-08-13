package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.TimeSpine
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue
import cc.monomer.metricflow.domain.manifest.validation.ValidationWarning

/**
 * Checks that time spines are configured sensibly: only one per granularity; smallest time
 * spine granularity is at most as coarse as the smallest time dimension's granularity.
 *
 * Port of `metricflow_semantic_interfaces/validations/time_spines.py::TimeSpineRule`.
 */
object TimeSpineRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (semanticManifest.semanticModels.isEmpty()) return issues
        val timeSpines = semanticManifest.projectConfiguration.timeSpines
        if (timeSpines.isEmpty()) return issues

        val byGran = mutableMapOf<TimeGranularity, MutableList<TimeSpine>>()
        val duplicates = mutableSetOf<TimeGranularity>()
        for (ts in timeSpines) {
            val g = ts.primaryColumn.timeGranularity
            byGran.getOrPut(g) { mutableListOf() }.add(ts)
            if ((byGran[g]?.size ?: 0) > 1) duplicates.add(g)
        }
        if (duplicates.isNotEmpty()) {
            val duplicateMap = duplicates.associate { g ->
                g.name to byGran.getValue(g).map { it.nodeRelation.relationName }
            }
            issues.add(
                ValidationWarning(
                    message = "Only one time spine is supported per granularity. Got duplicates: $duplicateMap",
                ),
            )
        }

        val dimensionGranularities = semanticManifest.semanticModels
            .flatMap { it.dimensions }
            .mapNotNull { it.typeParams?.timeGranularity }
            .toSet()
        if (dimensionGranularities.isEmpty()) {
            issues.add(
                ValidationWarning(
                    message = "No time dimensions configured. To avoid unexpected query errors, configuring a " +
                        "time spine at or below the smallest time dimension granularity is recommended.",
                ),
            )
            return issues
        }
        val smallestDim = dimensionGranularities.minBy { it.toInt() }
        val smallestTimeSpine = byGran.keys.minBy { it.toInt() }
        if (smallestDim.toInt() < smallestTimeSpine.toInt()) {
            // Mirror Python's repr for the time spine grain: `TimeGranularity.QUARTER`.
            issues.add(
                ValidationWarning(
                    message = "To avoid unexpected query errors, configuring a time spine at or below the smallest time " +
                        "dimension granularity is recommended. Smallest time dimension granularity: " +
                        "${smallestDim.name}; Smallest time spine granularity: " +
                        "TimeGranularity.${smallestTimeSpine.name}",
                ),
            )
        }
        return issues
    }
}
