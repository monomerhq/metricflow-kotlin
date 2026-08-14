package cc.monomer.metricflow.application.engine.adapter

import cc.monomer.metricflow.application.engine.EngineDimension
import cc.monomer.metricflow.application.engine.EngineEntity
import cc.monomer.metricflow.application.engine.EngineMetric
import cc.monomer.metricflow.application.engine.EngineSavedQuery
import cc.monomer.metricflow.domain.manifest.model.Export
import cc.monomer.metricflow.domain.manifest.model.Metadata
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.SavedQueryQueryParams
import cc.monomer.metricflow.domain.manifest.model.SemanticLayerElementConfig
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationResults
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.MetricContext
import cc.monomer.metricflow.domain.manifest.validation.SavedQueryContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext
import cc.monomer.metricflow.domain.manifest.validation.ValidationContext
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssueContext
import cc.monomer.metricflow.domain.manifest.validation.ValidationFutureError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds JSON outputs that match the Python oracle CLI's responses byte-for-byte
 * (post-canonicalisation — i.e. structural equality, key order doesn't matter).
 *
 * Mirrors the shapes documented in [`python_oracle/cli/SCHEMA.md`](../../../../../../../../../python_oracle/cli/SCHEMA.md)
 * and produced by [`python_oracle/oracle/serialize.py`](../../../../../../../../../python_oracle/oracle/serialize.py).
 *
 * Used by [cc.monomer.metricflow.integration.diff.CaseRunner] for the
 * diff comparison: emit `JsonElement`, compare to the corpus' `expected.json`.
 */
object EngineJsonSerializer {

    fun dimensionToJson(dimension: EngineDimension): JsonObject = buildJsonObject {
        put("name", dimension.name)
        put("dunder_name", dimension.dunderName)
        put("qualified_name", dimension.dunderName)
        put("type", dimension.type.value)
        put(
            "granularity",
            dimension.typeParams?.timeGranularity?.value?.let { JsonPrimitive(it) } ?: JsonNull,
        )
        putJsonArray("entity_links") {
            for (link in dimension.entityLinks) add(JsonPrimitive(link.elementName))
        }
        put(
            "semantic_model_name",
            dimension.semanticModelReference?.semanticModelName?.let { JsonPrimitive(it) } ?: JsonNull,
        )
        put("is_partition", JsonPrimitive(dimension.isPartition))
        put("is_metric_time", JsonPrimitive(dimension.semanticModelReference == null))
        put("description", dimension.description?.let { JsonPrimitive(it) } ?: JsonNull)
        put("label", dimension.label?.let { JsonPrimitive(it) } ?: JsonNull)
        put("expr", dimension.expr?.let { JsonPrimitive(it) } ?: JsonNull)
        put("metadata", encodeOptional(dimension.metadata, Metadata.serializer()))
    }

    fun entityToJson(entity: EngineEntity): JsonObject = buildJsonObject {
        put("name", entity.name)
        put("type", entity.type.value)
        put("role", entity.role?.let { JsonPrimitive(it) } ?: JsonNull)
        put("semantic_model_name", entity.semanticModelReference.semanticModelName)
        put("description", entity.description?.let { JsonPrimitive(it) } ?: JsonNull)
        put("expr", entity.expr?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    fun metricToJson(metric: EngineMetric): JsonObject = buildJsonObject {
        put("name", metric.name)
        put("type", metric.type.value)
        put("description", metric.description?.let { JsonPrimitive(it) } ?: JsonNull)
        put("label", metric.label?.let { JsonPrimitive(it) } ?: JsonNull)
        put("type_params", encodeValue(metric.typeParams, MetricTypeParams.serializer()))
        put("filter", encodeOptional(metric.filter, WhereFilterIntersection.serializer()))
        put("metadata", encodeOptional(metric.metadata, Metadata.serializer()))
        put("config", encodeOptional(metric.config, SemanticLayerElementConfig.serializer()))
        putJsonArray("dimensions") {
            for (d in metric.dimensions) add(dimensionToJson(d))
        }
        putJsonArray("semantic_models") {
            for (s in metric.semanticModels) add(JsonPrimitive(s.semanticModelName))
        }
    }

    fun savedQueryToJson(savedQuery: EngineSavedQuery): JsonObject = buildJsonObject {
        put("name", savedQuery.name)
        put("description", savedQuery.description?.let { JsonPrimitive(it) } ?: JsonNull)
        put("label", savedQuery.label?.let { JsonPrimitive(it) } ?: JsonNull)
        put("query_params", encodeValue(savedQuery.queryParams, SavedQueryQueryParams.serializer()))
        put("metadata", encodeOptional(savedQuery.metadata, Metadata.serializer()))
        putJsonArray("exports") {
            for (e in savedQuery.exports) add(encodeValue(e, Export.serializer()))
        }
        putJsonArray("tags") {
            for (t in savedQuery.tags) add(JsonPrimitive(t))
        }
    }

    fun issueToJson(issue: ValidationIssue): JsonObject = buildJsonObject {
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

    fun validationResultsToJson(results: SemanticManifestValidationResults): JsonObject = buildJsonObject {
        put("issues", JsonArray(results.allIssues.map { issueToJson(it) }))
        put("error_count", JsonPrimitive(results.errors.size))
        put("future_error_count", JsonPrimitive(results.futureErrors.size))
        put("warning_count", JsonPrimitive(results.warnings.size))
        put("has_blocking_issues", JsonPrimitive(results.hasBlockingIssues))
    }

    fun metricsListToJson(metrics: List<EngineMetric>): JsonObject = buildJsonObject {
        putJsonArray("metrics") {
            for (m in metrics) add(metricToJson(m))
        }
    }

    fun dimensionsListToJson(dims: List<EngineDimension>): JsonObject = buildJsonObject {
        putJsonArray("dimensions") {
            for (d in dims) add(dimensionToJson(d))
        }
    }

    fun entitiesListToJson(entities: List<EngineEntity>): JsonObject = buildJsonObject {
        putJsonArray("entities") {
            for (e in entities) add(entityToJson(e))
        }
    }

    fun groupBysToJson(dims: List<EngineDimension>, entities: List<EngineEntity>): JsonObject = buildJsonObject {
        putJsonArray("dimensions") {
            for (d in dims) add(dimensionToJson(d))
        }
        putJsonArray("entities") {
            for (e in entities) add(entityToJson(e))
        }
    }

    fun savedQueriesListToJson(queries: List<EngineSavedQuery>): JsonObject = buildJsonObject {
        putJsonArray("saved_queries") {
            for (q in queries) add(savedQueryToJson(q))
        }
    }

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

    private fun fileContextJson(context: FileContext): JsonObject = buildJsonObject {
        put("file_name", context.fileName?.let { JsonPrimitive(it) } ?: JsonNull)
        put("line_number", context.lineNumber?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun <T> encodeOptional(value: T?, serializer: KSerializer<T>): JsonElement =
        if (value == null) JsonNull else encodeValue(value, serializer)

    private fun <T> encodeValue(value: T, serializer: KSerializer<T>): JsonElement =
        ManifestJson.encodeToJsonElement(serializer, value)
}
