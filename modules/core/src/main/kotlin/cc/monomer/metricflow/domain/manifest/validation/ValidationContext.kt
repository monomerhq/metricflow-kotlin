package cc.monomer.metricflow.domain.manifest.validation

import cc.monomer.metricflow.domain.manifest.model.Metadata
import cc.monomer.metricflow.domain.manifest.model.references.MetricModelReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/**
 * The "where in the manifest" pointer attached to every [ValidationIssue].
 *
 * Port of `metricflow_semantic_interfaces/validations/validator_helpers.py` — the Pydantic
 * `ValidationContext` `Union[FileContext, MetricContext, SemanticModelContext,
 * SemanticModelElementContext, SavedQueryContext, ValidationIssueContext]`.
 *
 * Each variant carries its own provenance fields, plus a [contextStr] method returning the
 * human-readable representation that metricflow uses inside log lines and error messages.
 * The strings are byte-for-byte identical to Python so the parity test on the corpus
 * `readable` field passes.
 *
 * Why a `sealed interface` (not a generic struct): metricflow's union shape needs each
 * variant's keys preserved exactly when serialized (the Python `BaseModel.dict()`-style
 * shape). A `sealed interface` makes the variants discoverable and lets `JsonSerializer`
 * dispatch on type — see [ValidationContextSerializer] for the encoder shape.
 */
sealed interface ValidationContext {
    /** Human-readable representation. Mirrors Python's `context.context_str()`. */
    fun contextStr(): String
}

/**
 * Source-file provenance — file name + (optional) line number.
 *
 * Port of `validator_helpers.py::FileContext`.
 */
data class FileContext(
    val fileName: String? = null,
    val lineNumber: Int? = null,
) : ValidationContext {
    override fun contextStr(): String {
        if (fileName == null) return ""
        var s = "in file `$fileName`"
        if (lineNumber != null) s += " on line #$lineNumber"
        return s
    }

    companion object {
        /** Build a [FileContext] from a [Metadata] block. */
        fun fromMetadata(metadata: Metadata?): FileContext = FileContext(
            fileName = metadata?.fileSlice?.filename,
            lineNumber = metadata?.fileSlice?.startLineNumber,
        )
    }
}

/**
 * Context for an issue tied to a specific metric.
 *
 * Port of `validator_helpers.py::MetricContext`.
 */
data class MetricContext(
    val fileContext: FileContext,
    val metric: MetricModelReference,
) : ValidationContext {
    override fun contextStr(): String =
        "with metric `${metric.metricName}` ${fileContext.contextStr()}"
}

/**
 * Context for an issue tied to a semantic model as a whole.
 *
 * Port of `validator_helpers.py::SemanticModelContext`.
 */
data class SemanticModelContext(
    val fileContext: FileContext,
    val semanticModel: SemanticModelReference,
) : ValidationContext {
    override fun contextStr(): String =
        "with semantic model `${semanticModel.semanticModelName}` ${fileContext.contextStr()}"
}

/**
 * Context for an issue tied to a single element (measure / dimension / entity) inside a model.
 *
 * Port of `validator_helpers.py::SemanticModelElementContext`.
 */
data class SemanticModelElementContext(
    val fileContext: FileContext,
    val semanticModelElement: SemanticModelElementReference,
    val elementType: SemanticModelElementType,
) : ValidationContext {
    override fun contextStr(): String =
        "with ${elementType.value} `${semanticModelElement.elementName}` in semantic model " +
            "`${semanticModelElement.semanticModelName}` ${fileContext.contextStr()}"
}

/**
 * Context for an issue tied to a saved-query field.
 *
 * Port of `validator_helpers.py::SavedQueryContext`.
 *
 * Mirrors the Python message verbatim — including the apparent stutter
 * (`"with a metric in saved query \`metric\`"`); that's literally what Python emits.
 */
data class SavedQueryContext(
    val fileContext: FileContext,
    val elementType: SavedQueryElementType,
    val elementValue: String,
) : ValidationContext {
    override fun contextStr(): String =
        "with a ${elementType.value} in saved query `${elementType.value}` " +
            fileContext.contextStr()
}

/**
 * Generic "named object" context used by [cc.monomer.metricflow.domain.manifest.validation.rules.UniqueAndValidNameRule]
 * for top-level objects (metrics / semantic models / saved queries) where the more-specific
 * contexts don't apply.
 *
 * Port of `validator_helpers.py::ValidationIssueContext`.
 */
data class ValidationIssueContext(
    val fileContext: FileContext,
    val objectType: String,
    val objectName: String,
) : ValidationContext {
    override fun contextStr(): String =
        "with $objectType `$objectName` ${fileContext.contextStr()}"
}
