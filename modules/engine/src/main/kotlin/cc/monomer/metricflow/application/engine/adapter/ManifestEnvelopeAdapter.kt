package cc.monomer.metricflow.application.engine.adapter

import cc.monomer.metricflow.protocol.v1.ManifestEnvelope
import cc.monomer.metricflow.protocol.v1.SqlEngineType
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SavedQuery
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformer
import cc.monomer.metricflow.domain.sql.render.SqlEngine

/**
 * Translates a wire-shaped [ManifestEnvelope] into a fully-hydrated
 * [SemanticManifest] (plus the chosen [SqlEngine]).
 *
 * The wire envelope carries each of the four manifest sections (semantic
 * models, metrics, project configuration, saved queries) as one or more
 * JSON-string entries. We parse each entry through the canonical
 * [ManifestJson] (snake_case naming + strict unknown-key detection) and
 * assemble the [SemanticManifest] data class.
 *
 * Every command then runs the manifest through
 * [SemanticManifestTransformer.transform] — Python applies the transformer
 * before any RPC except hand-rolled debug paths, and PROGRESS.md confirms the
 * Kotlin contract has the same behaviour.
 *
 * Field-name preservation: this adapter never renames or reshapes any
 * manifest concept; the input JSON strings are byte-identical to what the
 * Python `oracle.manifest.build_manifest_from_input` helper produces.
 */
object ManifestEnvelopeAdapter {

    /**
     * Parse and transform the envelope. Throws [kotlinx.serialization.SerializationException]
     * on malformed JSON, or [IllegalArgumentException] if a required section is missing.
     */
    fun build(envelope: ManifestEnvelope): BuiltManifest {
        val semanticModels: List<SemanticModel> = envelope.semanticModelsJsonList.map {
            ManifestJson.decodeFromString(SemanticModel.serializer(), it)
        }
        val metrics: List<Metric> = envelope.metricsJsonList.map {
            ManifestJson.decodeFromString(Metric.serializer(), it)
        }
        require(envelope.projectConfigurationJson.isNotEmpty()) {
            "ManifestEnvelope.project_configuration_json must be set"
        }
        val projectConfiguration = ManifestJson.decodeFromString(
            ProjectConfiguration.serializer(),
            envelope.projectConfigurationJson,
        )
        val savedQueries: List<SavedQuery> = envelope.savedQueriesJsonList.map {
            ManifestJson.decodeFromString(SavedQuery.serializer(), it)
        }
        val raw = SemanticManifest(
            semanticModels = semanticModels,
            metrics = metrics,
            projectConfiguration = projectConfiguration,
            savedQueries = savedQueries,
        )
        val transformed = SemanticManifestTransformer.transform(raw)
        val sqlEngine = sqlEngineFromProto(envelope.sqlEngine)
        return BuiltManifest(manifest = transformed, sqlEngine = sqlEngine)
    }

    /**
     * Inverse of the proto enum mapping. Unspecified defaults to Trino, which
     * mirrors the Python oracle's "default dialect when none given" behaviour.
     */
    private fun sqlEngineFromProto(type: SqlEngineType): SqlEngine = when (type) {
        SqlEngineType.SQL_ENGINE_TYPE_TRINO,
        SqlEngineType.SQL_ENGINE_TYPE_UNSPECIFIED,
        SqlEngineType.UNRECOGNIZED -> SqlEngine.TRINO
        SqlEngineType.SQL_ENGINE_TYPE_BIGQUERY -> SqlEngine.BIGQUERY
        SqlEngineType.SQL_ENGINE_TYPE_SNOWFLAKE -> SqlEngine.SNOWFLAKE
        SqlEngineType.SQL_ENGINE_TYPE_DATABRICKS -> SqlEngine.DATABRICKS
        SqlEngineType.SQL_ENGINE_TYPE_REDSHIFT -> SqlEngine.REDSHIFT
        SqlEngineType.SQL_ENGINE_TYPE_DUCKDB -> SqlEngine.DUCKDB
        SqlEngineType.SQL_ENGINE_TYPE_POSTGRES -> SqlEngine.POSTGRES
    }
}

/**
 * The output of [ManifestEnvelopeAdapter.build]: the transformed manifest +
 * the resolved SQL engine target.
 */
data class BuiltManifest(
    val manifest: SemanticManifest,
    val sqlEngine: SqlEngine,
)
