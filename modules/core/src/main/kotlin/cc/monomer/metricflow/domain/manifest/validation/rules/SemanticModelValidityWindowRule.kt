package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelContext
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Checks SCD validity window dimensions: exactly two validity-param dimensions per model,
 * one marked `is_start` and the other `is_end`, paired with a `natural` entity but no other
 * primary/unique entities or measures.
 *
 * Port of `metricflow_semantic_interfaces/validations/semantic_models.py::SemanticModelValidityWindowRule`.
 */
object SemanticModelValidityWindowRule : SemanticManifestValidationRule {
    private const val REQUIREMENTS = "Semantic models using dimension validity params to define a validity window " +
        "must have exactly two time dimensions with validity params specified - one marked `is_start` and the " +
        "other marked `is_end`."

    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (semanticModel in semanticManifest.semanticModels) {
            issues.addAll(validateSemanticModel(semanticModel))
        }
        return issues
    }

    private fun validateSemanticModel(semanticModel: SemanticModel): List<ValidationIssue> {
        val validityParamDims = semanticModel.dimensions.filter { it.validityParams != null }
        if (validityParamDims.isEmpty()) return emptyList()

        val issues = mutableListOf<ValidationIssue>()
        val context = SemanticModelContext(
            fileContext = FileContext.fromMetadata(semanticModel.metadata),
            semanticModel = SemanticModelReference(semanticModelName = semanticModel.name),
        )
        val validityParamDimensionNames = validityParamDims.map { it.name }
        var startDimNames = validityParamDims
            .filter { it.validityParams?.isStart == true }
            .map { it.name }
        val endDimNames = validityParamDims
            .filter { it.validityParams?.isEnd == true }
            .map { it.name }
        val numStart = startDimNames.size
        val numEnd = endDimNames.size

        if (validityParamDims.size == 1 && numStart == 1 && numEnd == 1) {
            issues.add(
                ValidationError(
                    context = context,
                    message = "Semantic model ${semanticModel.name} has a single validity param dimension that defines its " +
                        "window: `${validityParamDimensionNames[0]}`. This is not a currently supported configuration! " +
                        "$REQUIREMENTS If you have one column defining a window, as in a daily snapshot table, you can " +
                        "define a separate dimension and increment the time value in the `expr` field as a work-around.",
                ),
            )
        } else if (validityParamDims.size != 2) {
            issues.add(
                ValidationError(
                    context = context,
                    message = "Semantic model ${semanticModel.name} has ${validityParamDims.size} dimensions defined with " +
                        "validity params. They are: $validityParamDimensionNames. There must be either zero or two! " +
                        "If you wish to define a validity window for this semantic model, please follow these " +
                        "requirements: $REQUIREMENTS",
                ),
            )
        } else if (numStart != 1 || numEnd != 1) {
            // Python bug-mirror: this branch sets `start_dim_names = []` before using it.
            startDimNames = emptyList()
            issues.add(
                ValidationError(
                    context = context,
                    message = "Semantic model ${semanticModel.name} has two validity param dimensions defined, but does not " +
                        "have exactly one each marked with is_start and is_end! Dimensions: " +
                        "$validityParamDimensionNames. is_start dimensions: $startDimNames. is_end dimensions: " +
                        "$endDimNames. $REQUIREMENTS",
                ),
            )
        }

        val primaryOrUnique = semanticModel.entities
            .filter { it.type == EntityType.PRIMARY || it.type == EntityType.UNIQUE }
        if (semanticModel.entities.none { it.type == EntityType.NATURAL }) {
            issues.add(
                ValidationError(
                    context = context,
                    message = "Semantic model ${semanticModel.name} has validity param dimensions defined, but does not have " +
                        "an entity with type `natural` set. The natural key for this semantic model is what we use to " +
                        "process a validity window join. Primary or unique entities, if any, might be suitable for " +
                        "use as natural keys: (${primaryOrUnique.map { it.name }}).",
                ),
            )
        }
        if (primaryOrUnique.isNotEmpty()) {
            issues.add(
                ValidationError(
                    context = context,
                    message = "Semantic model ${semanticModel.name} has validity param dimensions defined and also has one or " +
                        "more entities designated as `primary` or `unique`. This is not yet supported, as we do not " +
                        "currently process joins against these key types for semantic models with validity windows " +
                        "specified.",
                ),
            )
        }
        if (semanticModel.measures.isNotEmpty()) {
            val measureNames = semanticModel.measures.map { it.name }
            issues.add(
                ValidationError(
                    context = context,
                    message = "Semantic model ${semanticModel.name} has both measures and validity param dimensions defined. " +
                        "This is not currently supported! Please remove either the measures or the validity params. " +
                        "Measure names: $measureNames. Validity param dimension names: " +
                        "$validityParamDimensionNames.",
                ),
            )
        }
        return issues
    }
}
