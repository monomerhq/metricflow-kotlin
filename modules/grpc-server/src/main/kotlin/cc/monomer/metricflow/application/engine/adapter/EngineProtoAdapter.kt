package cc.monomer.metricflow.application.engine.adapter

import cc.monomer.metricflow.application.engine.EngineDimension
import cc.monomer.metricflow.application.engine.EngineEntity
import cc.monomer.metricflow.application.engine.EngineMetric
import cc.monomer.metricflow.application.engine.EngineSavedQuery
import cc.monomer.metricflow.protocol.v1.Dimension
import cc.monomer.metricflow.protocol.v1.Entity
import cc.monomer.metricflow.protocol.v1.Export
import cc.monomer.metricflow.protocol.v1.Metric
import cc.monomer.metricflow.protocol.v1.SavedQuery
import cc.monomer.metricflow.protocol.v1.ValidationIssue
import cc.monomer.metricflow.protocol.v1.ValidationIssueLevel
import cc.monomer.metricflow.domain.manifest.model.Export as ManifestExport
import cc.monomer.metricflow.domain.manifest.model.Metadata
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.SavedQueryQueryParams
import cc.monomer.metricflow.domain.manifest.model.SemanticLayerElementConfig
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.MetricContext
import cc.monomer.metricflow.domain.manifest.validation.SavedQueryContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext
import cc.monomer.metricflow.domain.manifest.validation.ValidationContext
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationFutureError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue as DomainValidationIssue
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssueContext
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssueLevel as DomainValidationIssueLevel
import cc.monomer.metricflow.domain.manifest.validation.ValidationWarning
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Converts domain-side engine DTOs and validation issues into their protobuf
 * representations.
 *
 * Two important rules baked in here:
 *
 * 1. **Field-by-field translation.** Every protobuf message field maps to one
 *    domain field. No new business logic — that lives in
 *    [cc.monomer.metricflow.application.engine.MetricFlowEngine].
 * 2. **JSON-string fields are byte-equivalent to the Python oracle's output.**
 *    Where the proto carries `*_json` strings (e.g. `Metric.type_params_json`),
 *    we emit the exact JSON shape that Python's
 *    `oracle.serialize.to_jsonable` produces. The reusable helper is
 *    [encodeToCompactJson].
 */
object EngineProtoAdapter {

    /** A compact JSON formatter that produces the same shape Python's `json.dumps` does (no extra whitespace). */
    private val compactJson: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
    }

    fun toProto(dimension: EngineDimension): Dimension {
        val builder = Dimension.newBuilder()
        builder.name = dimension.name
        builder.dunderName = dimension.dunderName
        builder.qualifiedName = dimension.dunderName
        builder.type = dimension.type.value
        dimension.typeParams?.timeGranularity?.let { builder.granularity = it.value }
        for (link in dimension.entityLinks) {
            builder.addEntityLinks(link.elementName)
        }
        dimension.semanticModelReference?.let { builder.semanticModelName = it.semanticModelName }
        builder.isPartition = dimension.isPartition
        builder.isMetricTime = dimension.semanticModelReference == null
        dimension.description?.let { builder.description = it }
        dimension.label?.let { builder.label = it }
        dimension.expr?.let { builder.expr = it }
        builder.metadataJson = jsonStringOf(dimension.metadata, Metadata.serializer())
        return builder.build()
    }

    fun toProto(entity: EngineEntity): Entity {
        val builder = Entity.newBuilder()
        builder.name = entity.name
        builder.type = entity.type.value
        entity.role?.let { builder.role = it }
        builder.semanticModelName = entity.semanticModelReference.semanticModelName
        entity.description?.let { builder.description = it }
        entity.expr?.let { builder.expr = it }
        return builder.build()
    }

    fun toProto(metric: EngineMetric): Metric {
        val builder = Metric.newBuilder()
        builder.name = metric.name
        builder.type = metric.type.value
        metric.description?.let { builder.description = it }
        metric.label?.let { builder.label = it }
        builder.typeParamsJson = compactJson.encodeToString(MetricTypeParams.serializer(), metric.typeParams)
        builder.filterJson = jsonStringOf(metric.filter, WhereFilterIntersection.serializer())
        builder.metadataJson = jsonStringOf(metric.metadata, Metadata.serializer())
        builder.configJson = jsonStringOf(metric.config, SemanticLayerElementConfig.serializer())
        for (dim in metric.dimensions) {
            builder.addDimensions(toProto(dim))
        }
        for (sm in metric.semanticModels) {
            builder.addSemanticModels(sm.semanticModelName)
        }
        return builder.build()
    }

    fun toProto(savedQuery: EngineSavedQuery): SavedQuery {
        val builder = SavedQuery.newBuilder()
        builder.name = savedQuery.name
        savedQuery.description?.let { builder.description = it }
        savedQuery.label?.let { builder.label = it }
        builder.queryParamsJson = compactJson.encodeToString(
            SavedQueryQueryParams.serializer(),
            savedQuery.queryParams,
        )
        builder.metadataJson = jsonStringOf(savedQuery.metadata, Metadata.serializer())
        for (export in savedQuery.exports) {
            builder.addExports(toProto(export))
        }
        for (tag in savedQuery.tags) builder.addTags(tag)
        return builder.build()
    }

    fun toProto(issue: DomainValidationIssue): ValidationIssue {
        val builder = ValidationIssue.newBuilder()
        builder.level = when (issue.level) {
            DomainValidationIssueLevel.ERROR -> ValidationIssueLevel.VALIDATION_ISSUE_LEVEL_ERROR
            DomainValidationIssueLevel.FUTURE_ERROR -> ValidationIssueLevel.VALIDATION_ISSUE_LEVEL_FUTURE_ERROR
            DomainValidationIssueLevel.WARNING -> ValidationIssueLevel.VALIDATION_ISSUE_LEVEL_WARNING
        }
        builder.message = issue.message
        builder.contextJson = issue.context?.let { encodeContext(it).toString() }.orEmpty()
        builder.contextStr = issue.context?.contextStr().orEmpty()
        issue.extraDetail?.let { builder.extraDetail = it }
        builder.readable = issue.asReadableStr()
        if (issue is ValidationFutureError) {
            builder.errorDate = issue.errorDate.toString()
        }
        return builder.build()
    }

    /**
     * Serialise a [DomainValidationIssue] to the canonical JSON dict produced
     * by Python's `oracle.serialize.issue_to_dict`. Used by the diff-runner to
     * compare against `corpus/<case>/expected.json` without going through the
     * proto wire shape.
     */
    fun toCanonicalJson(issue: DomainValidationIssue): JsonObject = buildJsonObject {
        put("level", issue.level.name)
        put("message", issue.message)
        val ctx = issue.context
        if (ctx == null) {
            put("context", JsonNull)
        } else {
            put("context", encodeContext(ctx))
        }
        put("context_str", ctx?.contextStr().orEmpty())
        put("extra_detail", issue.extraDetail?.let { JsonPrimitive(it) } ?: JsonNull)
        put("readable", issue.asReadableStr())
        if (issue is ValidationFutureError) {
            put("error_date", issue.errorDate.toString())
        }
    }

    private fun toProto(export: ManifestExport): Export {
        val builder = Export.newBuilder()
        builder.name = export.name
        builder.exportDestinationType = export.config.exportAs.name.lowercase()
        export.config.schemaName?.let { builder.schemaName = it }
        export.config.alias?.let { builder.alias = it }
        return builder.build()
    }

    private inline fun <reified T : Any> jsonStringOf(value: T?, serializer: kotlinx.serialization.KSerializer<T>): String =
        if (value == null) "" else compactJson.encodeToString(serializer, value)

    private fun encodeContext(ctx: ValidationContext): JsonObject = when (ctx) {
        is FileContext -> buildJsonObject {
            put("file_name", ctx.fileName?.let { JsonPrimitive(it) } ?: JsonNull)
            put("line_number", ctx.lineNumber?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        is MetricContext -> buildJsonObject {
            put("file_context", fileContextJson(ctx.fileContext))
            putJsonObject("metric") {
                put("metric_name", ctx.metric.metricName)
            }
        }
        is SemanticModelContext -> buildJsonObject {
            put("file_context", fileContextJson(ctx.fileContext))
            putJsonObject("semantic_model") {
                put("semantic_model_name", ctx.semanticModel.semanticModelName)
            }
        }
        is SemanticModelElementContext -> buildJsonObject {
            put("file_context", fileContextJson(ctx.fileContext))
            putJsonObject("semantic_model_element") {
                put("semantic_model_name", ctx.semanticModelElement.semanticModelName)
                put("element_name", ctx.semanticModelElement.elementName)
            }
            put("element_type", ctx.elementType.value)
        }
        is SavedQueryContext -> buildJsonObject {
            put("file_context", fileContextJson(ctx.fileContext))
            put("element_type", ctx.elementType.value)
            put("element_value", ctx.elementValue)
        }
        is ValidationIssueContext -> buildJsonObject {
            put("file_context", fileContextJson(ctx.fileContext))
            put("object_type", ctx.objectType)
            put("object_name", ctx.objectName)
        }
    }

    private fun fileContextJson(fc: FileContext): JsonObject = buildJsonObject {
        put("file_name", fc.fileName?.let { JsonPrimitive(it) } ?: JsonNull)
        put("line_number", fc.lineNumber?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /**
     * Lookup of which proto field corresponds to the unused issue subclass.
     * Compile-time check that the sealed hierarchy is exhausted.
     */
    @Suppress("unused")
    private fun exhaustiveCheck(issue: DomainValidationIssue) = when (issue) {
        is ValidationError -> Unit
        is ValidationWarning -> Unit
        is ValidationFutureError -> Unit
    }
}
