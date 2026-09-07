package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlPlanRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiHopExplainTest {
    @Test
    fun `partition equality and two hop provenance survive grouped explain`() {
        val result = explain(
            manifestName = "partitioned_multi_hop_join_manifest",
            metricNames = listOf("txn_count"),
            groupByNames = listOf("account_id__customer_id__customer_name"),
        )

        assertContains(result.sql, "bridge_table_src_10000.customer_id = customer_table_src_10000.customer_id")
        assertContains(result.sql, "DATETIME_TRUNC(bridge_table_src_10000.ds_partitioned, day) = DATETIME_TRUNC(customer_table_src_10000.ds_partitioned, day)")
        assertContains(result.sql, "DATETIME_TRUNC(account_month_txns_src_10000.ds_partitioned, day) = subq_11.ds_partitioned__day")
        assertEquals(1, Regex("SUM\\(account_month_txns_src_10000\\.txn_count\\)").findAll(result.sql).count(), result.sql)
        assertRelations(result, "account_month_txns", "bridge_table", "customer_table")
        assertSemanticModels(result, "account_month_txns", "bridge_table", "customer_table")
    }

    @Test
    fun `query dimension filter keeps intermediate two hop join`() {
        val result = explain(
            manifestName = "partitioned_multi_hop_join_manifest",
            metricNames = listOf("txn_count"),
            whereConstraints = listOf(
                "{{ Dimension('customer_id__customer_name', entity_path=['account_id']) }} IS NOT NULL",
            ),
        )

        assertContains(result.sql, "WHERE account_id__customer_id__customer_name IS NOT NULL")
        assertContains(result.sql, "bridge_table_src_10000.customer_id = customer_table_src_10000.customer_id")
        assertContains(result.sql, "DATETIME_TRUNC(bridge_table_src_10000.ds_partitioned, day) = DATETIME_TRUNC(customer_table_src_10000.ds_partitioned, day)")
        assertContains(result.sql, "DATETIME_TRUNC(account_month_txns_src_10000.ds_partitioned, day) = subq_11.ds_partitioned__day")
        assertEquals(1, Regex("SUM\\(txn_count\\)").findAll(result.sql).count(), result.sql)
        assertRelations(result, "account_month_txns", "bridge_table", "customer_table")
        assertSemanticModels(result, "account_month_txns", "bridge_table", "customer_table")
    }

    @Test
    fun `stored metric filter keeps intermediate two hop join`() {
        val result = explain(
            manifestName = "partitioned_multi_hop_join_metric_filter_manifest",
            metricNames = listOf("filtered_txn_count"),
        )

        assertContains(result.sql, "WHERE account_id__customer_id__customer_name IS NOT NULL")
        assertContains(result.sql, "bridge_table_src_10000.customer_id = customer_table_src_10000.customer_id")
        assertContains(result.sql, "DATETIME_TRUNC(bridge_table_src_10000.ds_partitioned, day) = DATETIME_TRUNC(customer_table_src_10000.ds_partitioned, day)")
        assertContains(result.sql, "DATETIME_TRUNC(account_month_txns_src_10000.ds_partitioned, day) = subq_11.ds_partitioned__day")
        assertEquals(1, Regex("SUM\\(filtered_txn_count\\)").findAll(result.sql).count(), result.sql)
        assertEquals(1, Regex("account_month_txns_src_10000\\.txn_count").findAll(result.sql).count(), result.sql)
        assertRelations(result, "account_month_txns", "bridge_table", "customer_table")
        assertSemanticModels(result, "account_month_txns", "bridge_table", "customer_table")
    }

    private fun explain(
        manifestName: String,
        metricNames: List<String>,
        groupByNames: List<String> = emptyList(),
        whereConstraints: List<String> = emptyList(),
    ): MetricFlowExplainResult {
        val manifestFile = File(
            System.getProperty("metricflow.repoRoot"),
            "corpus/manifests/$manifestName.json",
        )
        val manifest = ManifestJson.decodeFromString(
            SemanticManifest.serializer(),
            manifestFile.readText(),
        )
        return MetricFlowEngine(
            semanticManifest = manifest,
            sqlPlanRendererRegistry = SqlPlanRendererRegistry.of(
                SqlPlanRendererRegistration(SqlEngine.BIGQUERY, BigQuerySqlPlanRenderer()),
            ),
        ).explain(
            MetricFlowExplainRequest(
                metricNames = metricNames,
                groupByNames = groupByNames,
                whereConstraints = whereConstraints,
                orderByNames = emptyList(),
                limit = null,
                timeConstraintStart = null,
                timeConstraintEnd = null,
                savedQueryName = null,
                minMaxOnly = false,
                applyGroupBy = true,
                orderOutputColumnsByInputOrder = false,
                dialect = SqlEngine.BIGQUERY,
            ),
        )
    }

    private fun assertRelations(result: MetricFlowExplainResult, vararg expected: String) {
        val actual = result.usedRelations.map { it.tableName }.toSet()
        assertEquals(expected.toSet(), actual, result.usedRelations.toString())
    }

    private fun assertSemanticModels(result: MetricFlowExplainResult, vararg expected: String) {
        val actual = result.queriedSemanticModels.map { it.semanticModelName }.toSet()
        assertEquals(expected.toSet(), actual, result.queriedSemanticModels.toString())
        assertTrue(actual.isNotEmpty())
    }
}
