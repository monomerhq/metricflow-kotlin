package cc.monomer.metricflow.integration.diff

import cc.monomer.metricflow.application.engine.GroupByOrderByAttribute
import cc.monomer.metricflow.application.engine.MetricFlowEngine
import cc.monomer.metricflow.application.engine.adapter.EngineJsonSerializer
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SavedQuery
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformer
import cc.monomer.metricflow.integration.diff.sqlnorm.SqlNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Runs one corpus case **in-process** against the Kotlin engine and compares
 * the produced output to `expected.json` from the Python oracle.
 *
 * The runner deliberately calls [MetricFlowEngine] directly (not over gRPC):
 * the gRPC service is exercised by the smoke test and by external callers, but
 * the differential comparison just wants the engine's canonical JSON output —
 * the wire shape is incidental. This is also what lets the runner produce
 * structural diffs (sorted-list comparison, JsonNull/JsonPrimitive handling)
 * without re-decoding protobuf strings.
 *
 * Outcome rules:
 *
 * - `PASS` — every Kotlin output key matches Python within the normalisation
 *   rules below.
 * - `FAIL` — Kotlin produced an output but it disagreed with Python.
 * - `UNIMPLEMENTED` — the engine threw [NotImplementedError]; the case waits
 *   for a post-W10 wave.
 * - `ERROR` — any other failure (unexpected throw, malformed corpus).
 *
 * Normalisation rules applied before comparison:
 *
 * 1. **Sort lists keyed on identifying fields.** `metrics`, `dimensions`,
 *    `entities`, `saved_queries`, `issues` are sorted (by `name` /
 *    `dunder_name` / `(level,message,context_str)`), then compared. Python
 *    already sorts these, so the sort is idempotent on the Python side.
 * 2. **Per-element sort of `dimensions` inside a metric and `entity_links`
 *    inside a dimension.** Python returns these pre-sorted; we re-sort to
 *    be defensive.
 * 3. **Ignore unrecognised top-level keys.** Currently zero such keys exist,
 *    but the comparator is permissive to allow Python to add extras.
 */
class CaseRunner {

    /** Strict JSON parser for the corpus's `expected.json` files. */
    private val strictJson: Json = Json { ignoreUnknownKeys = false }

    fun run(case: CorpusCase): CaseResult {
        return try {
            val request = File(case.caseDir, "request.json")
            if (!request.isFile) {
                return CaseResult(case.caseId, CaseOutcome.ERROR, "Missing request.json")
            }
            // Explain / explain_get_dimension_values store expected SQL under
            // `expected/<dialect>.sql`, one file per rendering dialect. They are
            // routed through the per-dialect SQL comparison path. Every other
            // subcommand uses `expected.json` (one canonical JSON output).
            if (case.subcommand == "explain" || case.subcommand == "explain_get_dimension_values") {
                return runExplainCase(case, request)
            }
            val expected = File(case.caseDir, "expected.json")
            if (!expected.isFile) {
                return CaseResult(
                    case.caseId,
                    CaseOutcome.ERROR,
                    "expected.json absent for non-explain subcommand '${case.subcommand}'",
                )
            }
            val manifest = loadManifest(request)
            val actual = dispatch(case, manifest)
            val expectedJson = strictJson.parseToJsonElement(expected.readText())
            compare(case, expectedJson, actual)
        } catch (e: NotImplementedError) {
            CaseResult(case.caseId, CaseOutcome.UNIMPLEMENTED, e.message.orEmpty())
        } catch (e: Throwable) {
            CaseResult(
                case.caseId,
                CaseOutcome.ERROR,
                "Exception: ${e::class.simpleName}: ${e.message ?: ""}",
            )
        }
    }

    /**
     * Per-dialect SQL diff for explain / explain_get_dimension_values cases.
     *
     * Corpus shape: `expected/<dialect>.sql` (one file per dialect listed in
     * the case's `meta.json::dialect_set`). For each dialect we:
     *
     * 1. Read the expected SQL.
     * 2. Invoke the Kotlin engine's `explain` (or `explainGetDimensionValues`)
     *    request — at present the engine returns the same SQL regardless of
     *    dialect because the dialect plumbing for renderer selection is not
     *    yet wired into [MetricFlowEngine] (engine takes no dialect arg as of
     *    W14b). When the engine learns to render per-dialect, this call will
     *    accept the dialect parameter.
     * 3. Normalize both with [SqlNormalizer] (3 rules: line endings, trailing
     *    whitespace, blank lines).
     * 4. Compare byte-for-byte after normalization.
     *
     * Outcome aggregation is **case-level**: PASS iff every dialect matches,
     * else FAIL with a brief per-dialect breakdown. This matches how Python's
     * differential test suite reports — one case = one row in the report.
     *
     * If the engine throws [NotImplementedError] on the first dialect, the
     * case is UNIMPLEMENTED (the deferral message names the missing layer).
     * Subsequent dialects share the same builder/converter pipeline, so the
     * first throw is representative; we don't iterate further.
     */
    private fun runExplainCase(case: CorpusCase, request: File): CaseResult {
        val expectedDir = File(case.caseDir, "expected")
        if (!expectedDir.isDirectory) {
            return CaseResult(
                case.caseId,
                CaseOutcome.ERROR,
                "explain case missing expected/ directory",
            )
        }
        val dialectFiles = expectedDir.listFiles { f -> f.isFile && f.name.endsWith(".sql") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (dialectFiles.isEmpty()) {
            return CaseResult(
                case.caseId,
                CaseOutcome.ERROR,
                "No expected/<dialect>.sql files for explain case",
            )
        }
        val manifest = loadManifest(request)
        val engine = MetricFlowEngine(manifest)
        val perDialect = mutableListOf<Pair<String, Boolean>>()
        var firstMismatchDetail: String? = null
        for (sqlFile in dialectFiles) {
            val dialect = sqlFile.nameWithoutExtension
            val expectedSql = sqlFile.readText()
            val actualSql = renderExplainSql(case, engine, dialect)
            val normalizedExpected = SqlNormalizer.normalize(expectedSql)
            val normalizedActual = SqlNormalizer.normalize(actualSql)
            val match = normalizedExpected == normalizedActual
            perDialect.add(dialect to match)
            if (!match && firstMismatchDetail == null) {
                if (System.getenv("DIFF_RUNNER_DEBUG_SQL") == "1") {
                    System.err.println("=== ACTUAL SQL for ${case.caseId} / $dialect ===")
                    System.err.println(actualSql)
                    System.err.println("=== END ACTUAL SQL ===")
                }
                firstMismatchDetail = summarizeSqlMismatch(dialect, normalizedExpected, normalizedActual)
            }
        }
        val allPass = perDialect.all { it.second }
        return if (allPass) {
            CaseResult(case.caseId, CaseOutcome.PASS, "all ${perDialect.size} dialects matched")
        } else {
            val failed = perDialect.filter { !it.second }.map { it.first }
            CaseResult(
                case.caseId,
                CaseOutcome.FAIL,
                "${failed.size}/${perDialect.size} dialects failed (${failed.joinToString(",")}). " +
                    "First mismatch: ${firstMismatchDetail ?: "<unknown>"}",
            )
        }
    }

    /**
     * Drive the engine's explain path for a single dialect.
     *
     * Maps the dialect name (file stem of `expected/<dialect>.sql`) to a
     * [cc.monomer.metricflow.domain.sql.render.SqlEngine] enum value so the engine can
     * select the right renderer. Unknown dialect names raise an error — the corpus's
     * meta.json `dialect_set` is the source of truth.
     */
    private fun renderExplainSql(case: CorpusCase, engine: MetricFlowEngine, dialect: String): String {
        val engineDialect = dialectFromFileStem(dialect)
        return when (case.subcommand) {
            "explain" -> {
                val element = dispatchExplain(case, engine, engineDialect)
                (element as? JsonPrimitive)?.contentOrNull
                    ?: error("explain dispatcher returned non-string JSON: $element")
            }
            "explain_get_dimension_values" -> {
                val element = dispatchExplainGetDimensionValues(case, engine, engineDialect)
                (element as? JsonPrimitive)?.contentOrNull
                    ?: error("explain_get_dimension_values dispatcher returned non-string JSON: $element")
            }
            else -> error("renderExplainSql called for unsupported subcommand '${case.subcommand}'")
        }
    }

    /** File-stem-to-enum mapping for the dialect set defined in `meta.json`. */
    private fun dialectFromFileStem(stem: String): cc.monomer.metricflow.domain.sql.render.SqlEngine =
        when (stem.lowercase()) {
            "trino" -> cc.monomer.metricflow.domain.sql.render.SqlEngine.TRINO
            "bigquery" -> cc.monomer.metricflow.domain.sql.render.SqlEngine.BIGQUERY
            "snowflake" -> cc.monomer.metricflow.domain.sql.render.SqlEngine.SNOWFLAKE
            "databricks" -> cc.monomer.metricflow.domain.sql.render.SqlEngine.DATABRICKS
            "redshift" -> cc.monomer.metricflow.domain.sql.render.SqlEngine.REDSHIFT
            "duckdb" -> cc.monomer.metricflow.domain.sql.render.SqlEngine.DUCKDB
            "postgres" -> cc.monomer.metricflow.domain.sql.render.SqlEngine.POSTGRES
            else -> error("Unknown dialect file stem: $stem")
        }

    /** Short structural diff hint for a failed SQL comparison, capped to keep the report compact. */
    private fun summarizeSqlMismatch(dialect: String, expected: String, actual: String): String {
        val expLines = expected.split('\n')
        val actLines = actual.split('\n')
        val maxLines = maxOf(expLines.size, actLines.size)
        for (i in 0 until maxLines) {
            val e = expLines.getOrNull(i)
            val a = actLines.getOrNull(i)
            if (e != a) {
                return "$dialect line ${i + 1}: expected='${truncate(e ?: "<eof>", 80)}' " +
                    "actual='${truncate(a ?: "<eof>", 80)}'"
            }
        }
        return "$dialect: contents equal after trimming (unreachable)"
    }

    private fun loadManifest(requestFile: File): SemanticManifest {
        // Permissive parser: corpus request.json carries an `args` block that
        // isn't part of the manifest. Drop it the same way Python's
        // `build_manifest_from_input` does — by selecting just the four
        // manifest sections at the top level.
        val root = strictJson.parseToJsonElement(requestFile.readText()).jsonObject
        val semanticModels = root["semantic_models"]?.jsonArray?.map {
            ManifestJson.decodeFromJsonElement(SemanticModel.serializer(), it)
        }.orEmpty()
        val metrics = root["metrics"]?.jsonArray?.map {
            ManifestJson.decodeFromJsonElement(Metric.serializer(), it)
        }.orEmpty()
        val projectConfiguration = root["project_configuration"]?.let {
            ManifestJson.decodeFromJsonElement(ProjectConfiguration.serializer(), it)
        } ?: error("request.json missing project_configuration")
        val savedQueries = root["saved_queries"]?.jsonArray?.map {
            ManifestJson.decodeFromJsonElement(SavedQuery.serializer(), it)
        }.orEmpty()
        val raw = SemanticManifest(
            semanticModels = semanticModels,
            metrics = metrics,
            projectConfiguration = projectConfiguration,
            savedQueries = savedQueries,
        )
        return SemanticManifestTransformer.transform(raw)
    }

    private fun dispatch(case: CorpusCase, manifest: SemanticManifest): JsonElement {
        // validate_manifest must NOT construct a MetricFlowEngine — Python
        // builds the validator standalone (oracle/commands/validate_manifest.py),
        // so engine-init errors like "no primary entity" surface as validator
        // issues instead of crashing the process.
        if (case.subcommand == "validate_manifest") {
            val results = cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidator
                .withDefaultRules()
                .validate(manifest)
            return EngineJsonSerializer.validationResultsToJson(results)
        }
        val engine = MetricFlowEngine(manifest)
        return when (case.subcommand) {
            "validate_manifest" -> EngineJsonSerializer.validationResultsToJson(engine.validateManifest())
            "list_saved_queries" -> EngineJsonSerializer.savedQueriesListToJson(engine.listSavedQueries())
            "list_metrics" -> {
                val includeDimensions = case.args["include_dimensions"]?.jsonPrimitive?.booleanOrNull ?: true
                EngineJsonSerializer.metricsListToJson(engine.listMetrics(includeDimensions))
            }
            "list_dimensions" -> {
                val metricNames = case.args["metric_names"]?.jsonArray?.map { it.jsonPrimitive.content }
                EngineJsonSerializer.dimensionsListToJson(
                    engine.listDimensions(metricNames = metricNames, orderBy = GroupByOrderByAttribute.DUNDER_NAME),
                )
            }
            "entities_for_metrics" -> {
                val metricNames = case.args["metric_names"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                EngineJsonSerializer.entitiesListToJson(engine.entitiesForMetrics(metricNames))
            }
            "list_group_bys" -> {
                val metricNames = case.args["metric_names"]?.jsonArray?.map { it.jsonPrimitive.content }
                val includeDerived = case.args["include_derived_time_granularities"]
                    ?.jsonPrimitive?.booleanOrNull ?: false
                val orderBy = when (case.args["order_by"]?.jsonPrimitive?.contentOrNull?.uppercase()) {
                    "SEMANTIC_MODEL_NAME" -> GroupByOrderByAttribute.SEMANTIC_MODEL_NAME
                    else -> GroupByOrderByAttribute.DUNDER_NAME
                }
                val listing = engine.listGroupBys(
                    metricNames = metricNames,
                    includeDerivedTimeGranularities = includeDerived,
                    orderBy = orderBy,
                )
                EngineJsonSerializer.groupBysToJson(listing.dimensions, listing.entities)
            }
            "explain", "explain_get_dimension_values" -> throw IllegalStateException(
                "explain* subcommands are routed via runExplainCase; dispatch() should never see them",
            )
            else -> throw IllegalArgumentException("Unknown subcommand '${case.subcommand}'")
        }
    }

    /**
     * Dispatch `explain` corpus cases to [MetricFlowEngine.explain].
     *
     * As of W14 the engine probes the full chain (parser → builder → converter → renderer)
     * but at least one layer still has a deferred body. The [NotImplementedError] thrown
     * by that layer propagates up to the outer `catch` in [run] and is categorised as
     * UNIMPLEMENTED. Once the deferred bodies land (subsequent waves), this dispatcher
     * naturally produces a real SQL string that can be diff'd against the corpus's
     * `expected/<dialect>.sql` files.
     *
     * **Note** — the corpus stores explain outputs as one `.sql` file per dialect, not
     * one `expected.json`. Step 1 of [run] already short-circuits to UNIMPLEMENTED when
     * `expected.json` is absent, so we never reach this code path in production today.
     * The dispatcher exists so a future wave that stores explain SQL in `expected.json`
     * (or a future SQL-dialect comparator) has a working seam.
     */
    private fun dispatchExplain(
        case: CorpusCase,
        engine: MetricFlowEngine,
        dialect: cc.monomer.metricflow.domain.sql.render.SqlEngine?,
    ): JsonElement {
        val metricNames = case.args["metric_names"]?.jsonArray?.map { it.jsonPrimitive.content }
        val groupByNames = case.args["group_by_names"]?.jsonArray?.map { it.jsonPrimitive.content }
        val limit = case.args["limit"]?.jsonPrimitive?.intOrNull
        val whereConstraints = case.args["where_constraints"]?.jsonArray?.map { it.jsonPrimitive.content }
        val orderByNames = case.args["order_by_names"]?.jsonArray?.map { it.jsonPrimitive.content }
        val timeConstraintStart = case.args["time_constraint_start"]?.jsonPrimitive?.contentOrNull
        val timeConstraintEnd = case.args["time_constraint_end"]?.jsonPrimitive?.contentOrNull
        val savedQueryName = case.args["saved_query_name"]?.jsonPrimitive?.contentOrNull
        val minMaxOnly = case.args["min_max_only"]?.jsonPrimitive?.booleanOrNull ?: false
        val applyGroupBy = case.args["apply_group_by"]?.jsonPrimitive?.booleanOrNull ?: true
        val orderOutputColumnsByInputOrder =
            case.args["order_output_columns_by_input_order"]?.jsonPrimitive?.booleanOrNull ?: false
        val result = engine.explain(
            cc.monomer.metricflow.application.engine.MetricFlowExplainRequest(
                metricNames = metricNames,
                groupByNames = groupByNames,
                whereConstraints = whereConstraints,
                orderByNames = orderByNames,
                limit = limit,
                timeConstraintStart = timeConstraintStart,
                timeConstraintEnd = timeConstraintEnd,
                savedQueryName = savedQueryName,
                minMaxOnly = minMaxOnly,
                applyGroupBy = applyGroupBy,
                orderOutputColumnsByInputOrder = orderOutputColumnsByInputOrder,
                dialect = dialect,
            ),
        )
        return JsonPrimitive(result.sql)
    }

    private fun dispatchExplainGetDimensionValues(
        case: CorpusCase,
        engine: MetricFlowEngine,
        dialect: cc.monomer.metricflow.domain.sql.render.SqlEngine?,
    ): JsonElement {
        val metricNames = case.args["metric_names"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: emptyList()
        val getGroupByValues = case.args["get_group_by_values"]?.jsonPrimitive?.content
            ?: error("explain_get_dimension_values case missing 'get_group_by_values'")
        val timeConstraintStart = case.args["time_constraint_start"]?.jsonPrimitive?.contentOrNull
        val timeConstraintEnd = case.args["time_constraint_end"]?.jsonPrimitive?.contentOrNull
        val minMaxOnly = case.args["min_max_only"]?.jsonPrimitive?.booleanOrNull ?: false
        val result = engine.explainGetDimensionValues(
            cc.monomer.metricflow.application.engine.ExplainGetDimensionValuesRequest(
                metricNames = metricNames,
                getGroupByValues = getGroupByValues,
                timeConstraintStart = timeConstraintStart,
                timeConstraintEnd = timeConstraintEnd,
                minMaxOnly = minMaxOnly,
                dialect = dialect,
            ),
        )
        return JsonPrimitive(result.sql)
    }

    private fun compare(case: CorpusCase, expected: JsonElement, actual: JsonElement): CaseResult {
        val normalizedExpected = normalize(expected)
        val normalizedActual = normalize(actual)
        return if (normalizedExpected == normalizedActual) {
            CaseResult(case.caseId, CaseOutcome.PASS, "")
        } else {
            CaseResult(case.caseId, CaseOutcome.FAIL, summarizeMismatch(normalizedExpected, normalizedActual, path = "$"))
        }
    }

    /**
     * Canonicalisation step: sort lists by an identifying key (when present)
     * and recurse into nested structures. After normalisation two outputs are
     * equal iff structurally equivalent.
     */
    private fun normalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildSortedObject(element)
        is JsonArray -> JsonArray(element.map { normalize(it) })
        else -> element
    }

    private fun buildSortedObject(obj: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (key in obj.keys.sorted()) {
            val value = obj.getValue(key)
            out[key] = when {
                value is JsonArray && key in LIST_KEYS_WITH_SORT_KEY -> sortByKey(value, LIST_KEYS_WITH_SORT_KEY.getValue(key))
                else -> normalize(value)
            }
        }
        return JsonObject(out)
    }

    private fun sortByKey(array: JsonArray, sortKeys: List<String>): JsonArray {
        val items = array.map { normalize(it) }
        val sorted = items.sortedWith(Comparator { a, b ->
            for (k in sortKeys) {
                val av = extractKey(a, k)
                val bv = extractKey(b, k)
                val c = av.compareTo(bv)
                if (c != 0) return@Comparator c
            }
            0
        })
        return JsonArray(sorted)
    }

    private fun extractKey(value: JsonElement, key: String): String =
        when (value) {
            is JsonObject -> (value[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            else -> ""
        }

    /**
     * Walk into the first differing key recursively and report exactly which
     * nested location differs. Distinguishes between size mismatch, element
     * mismatch, and primitive divergence so the report points the reader at
     * the actual problem instead of a wall of JSON.
     */
    private fun summarizeMismatch(expected: JsonElement, actual: JsonElement, path: String): String {
        if (expected is JsonObject && actual is JsonObject) {
            val firstDiff = expected.keys.firstOrNull { k -> expected[k] != actual[k] }
                ?: actual.keys.firstOrNull { k -> k !in expected.keys }
            if (firstDiff != null) {
                val expV = expected[firstDiff] ?: JsonNull
                val actV = actual[firstDiff] ?: JsonNull
                return summarizeMismatch(expV, actV, path = "$path.$firstDiff")
            }
            return "no top-level diff at $path"
        }
        if (expected is JsonArray && actual is JsonArray) {
            if (expected.size != actual.size) {
                return "$path: array size expected=${expected.size} actual=${actual.size}"
            }
            val firstIdx = (0 until expected.size).firstOrNull { expected[it] != actual[it] }
            if (firstIdx != null) {
                return summarizeMismatch(expected[firstIdx], actual[firstIdx], path = "$path[$firstIdx]")
            }
            return "$path: arrays equal (unreachable)"
        }
        val expString = compactJson.encodeToString(JsonElement.serializer(), expected)
        val actString = compactJson.encodeToString(JsonElement.serializer(), actual)
        return "$path: expected=${truncate(expString, 160)} | actual=${truncate(actString, 160)}"
    }

    private fun truncate(s: String, max: Int): String = if (s.length > max) s.take(max) + "…" else s

    private val compactJson: Json = Json { prettyPrint = false }

    companion object {
        /**
         * Top-level keys whose arrays need to be sorted by an identifying child
         * before structural comparison. Python emits these in the same order;
         * we sort defensively because Kotlin's ordering may differ for cases
         * we haven't exercised yet.
         */
        private val LIST_KEYS_WITH_SORT_KEY: Map<String, List<String>> = mapOf(
            "metrics" to listOf("name"),
            "dimensions" to listOf("dunder_name", "semantic_model_name"),
            "entities" to listOf("name", "semantic_model_name"),
            "saved_queries" to listOf("name"),
            "issues" to listOf("level", "message", "context_str"),
            "entity_links" to listOf(),
            "tags" to listOf(),
            "semantic_models" to listOf(),
            "exports" to listOf("name"),
        )
    }
}

/** Outcome categories used by the diff-runner report. */
enum class CaseOutcome { PASS, FAIL, UNIMPLEMENTED, ERROR }

data class CaseResult(val caseId: String, val outcome: CaseOutcome, val detail: String)

/**
 * A parsed corpus case. Holds the case-dir handle so [CaseRunner] can load
 * `request.json` / `expected.json` on demand.
 */
data class CorpusCase(
    val caseId: String,
    val subcommand: String,
    val manifestId: String,
    val args: JsonObject,
    val dialectSet: List<String>,
    val caseDir: File,
)
