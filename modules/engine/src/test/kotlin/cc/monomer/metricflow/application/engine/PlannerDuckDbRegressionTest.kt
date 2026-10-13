package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformer
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.infrastructure.sql.render.duckdb.DuckDbSqlPlanRenderer
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Executes generated SQL only in verification; the public engine remains warehouse-free. */
class PlannerDuckDbRegressionTest {
    private val manifest: SemanticManifest = ManifestJson.decodeFromString(
        SemanticManifest.serializer(),
        File(System.getProperty("metricflow.repoRoot"), "corpus/manifests/planner_regressions.json").readText(),
    )

    private fun engine(input: SemanticManifest = manifest) = MetricFlowEngine(
        SemanticManifestTransformer.transform(input),
        SqlPlanRendererRegistry.of(SqlPlanRendererRegistration(SqlEngine.DUCKDB, DuckDbSqlPlanRenderer())),
    )

    private fun request(
        metric: String,
        groups: List<String> = emptyList(),
        filters: List<String> = emptyList(),
        start: String = "2025-10-13",
        end: String = "2026-10-12",
    ) = MetricFlowExplainRequest(
        metricNames = listOf(metric), groupByNames = groups, whereConstraints = filters,
        orderByNames = groups, limit = 1000, timeConstraintStart = start, timeConstraintEnd = end,
        savedQueryName = null, minMaxOnly = false, applyGroupBy = true,
        orderOutputColumnsByInputOrder = true, dialect = SqlEngine.DUCKDB,
    )

    private fun database(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:duckdb:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE SCHEMA acme")
                sql.execute("CREATE TABLE acme.accounts AS SELECT i AS account_id, CASE WHEN i <= 10 THEN 'North America' ELSE 'Europe' END AS region FROM range(1, 21) t(i)")
                sql.execute("CREATE TABLE acme.time_spine_day AS SELECT CAST(d AS DATE) date_day FROM generate_series(DATE '2025-10-01', DATE '2026-10-31', INTERVAL '1 day') t(d)")
                sql.execute("""
                    CREATE TABLE acme.daily_account_activity AS
                    SELECT CAST(account_id AS VARCHAR) || ':' || CAST(date_day AS VARCHAR) AS row_id,
                           account_id, date_day AS activity_date,
                           CASE WHEN account_id % 2 = 0 THEN 'Team' ELSE 'Pro' END AS plan,
                           CASE WHEN day(date_day) % 3 = 0 THEN 0 ELSE 1 END AS active_users,
                           CASE WHEN (account_id - 1) % 10 < 7 AND day(date_day) % 3 <> 0 THEN 1 ELSE 0 END AS core_actions
                    FROM acme.accounts CROSS JOIN acme.time_spine_day
                    WHERE date_day <= DATE '2026-10-12'
                """.trimIndent())
                // Duplicate activity rows protect COUNT DISTINCT across windows and joins.
                sql.execute("INSERT INTO acme.daily_account_activity SELECT row_id || ':extra', account_id, activity_date, plan, active_users, core_actions FROM acme.daily_account_activity WHERE account_id = 2")
                sql.execute("""
                    CREATE TABLE acme.monthly_account_mrr AS
                    SELECT CAST(account_id AS VARCHAR) || ':' || CAST(d AS VARCHAR) AS row_id,
                           account_id, CAST(d AS DATE) AS month,
                           CAST(CASE WHEN d = DATE '2026-10-12' THEN
                               CASE WHEN account_id = 1 THEN 40000 ELSE 27547.54 END
                               WHEN d = DATE '2026-09-12' THEN
                               CASE WHEN account_id = 1 THEN 30000 ELSE 31753.50 END
                               ELSE CASE WHEN account_id = 1 THEN 30000 ELSE 25000 END
                           END AS DECIMAL(12,2)) AS mrr_usd,
                           CAST(CASE WHEN account_id = 1 THEN 35000 ELSE 30000 END AS DECIMAL(12,2)) AS prior_mrr
                    FROM acme.accounts CROSS JOIN generate_series(DATE '2025-11-12', DATE '2026-10-12', INTERVAL '1 month') t(d)
                    WHERE account_id IN (1, 11)
                """.trimIndent())
                sql.execute("""
                    CREATE TABLE acme.irregular_snapshots AS
                    SELECT * FROM (VALUES
                        ('a', 1, DATE '2026-08-02', 10), ('b', 1, DATE '2026-08-09', 90),
                        ('c', 11, DATE '2026-08-03', 20), ('d', 11, DATE '2026-08-08', 80),
                        ('e', 1, DATE '2026-09-02', 30), ('f', 1, DATE '2026-09-09', 100),
                        ('g', 11, DATE '2026-09-03', 40), ('h', 11, DATE '2026-09-08', 200)
                    ) t(row_id, account_id, snapshot_date, value)
                """.trimIndent())
            }
            block(db)
        }
    }

    private data class Row(val keys: List<String>, val value: Double)

    private fun rows(db: Connection, sql: String): List<Row> = db.createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val count = result.metaData.columnCount
            buildList {
                while (result.next()) {
                    add(Row((1 until count).map { result.getString(it).removeSuffix(" 00:00:00.0").removeSuffix(" 00:00:00") }, result.getDouble(count)))
                }
            }
        }
    }

    private fun compare(db: Connection, request: MetricFlowExplainRequest, oracle: String): List<Row> {
        val generated = engine().explain(request).sql
        val actual = rows(db, generated)
        val expected = rows(db, oracle)
        assertEquals(expected.map { it.keys }, actual.map { it.keys }, generated)
        expected.zip(actual).forEach { (want, got) -> assertEquals(want.value, got.value, 0.000001, generated) }
        return actual
    }

    @Test
    fun `cumulative local and joined filters preserve seven day distinct windows and coarse ordering`() = database { db ->
        for (grain in listOf("day", "week", "month")) {
            for (region in listOf(false, true)) {
                for (plan in listOf(false, true)) {
                    for (metric in listOf("active_accounts", "core_accounts")) {
                        val filters = buildList {
                            if (region) add("{{ Dimension('account__region') }} IN ('North America')")
                            if (plan) add("{{ Dimension('account_day__plan') }} IN ('Team')")
                        }
                        val field = if (metric == "active_accounts") "active_users" else "core_actions"
                        val groups = listOf("metric_time__$grain") + if (plan) listOf("account_day__plan") else emptyList()
                        val planSelect = if (plan) ", 'Team' AS plan" else ""
                        val oracle = """
                            WITH periods AS (
                              SELECT date_trunc('$grain', date_day) AS bucket, MAX(date_day) AS endpoint
                              FROM acme.time_spine_day WHERE date_day BETWEEN DATE '2026-07-15' AND DATE '2026-10-12'
                              GROUP BY 1
                            )
                            SELECT CAST(bucket AS DATE)$planSelect,
                              (SELECT COUNT(DISTINCT a.account_id) FROM acme.daily_account_activity a
                               JOIN acme.accounts d USING(account_id)
                               WHERE a.activity_date BETWEEN endpoint - INTERVAL '6 days' AND endpoint
                                 AND a.$field >= 1
                                 ${if (region) "AND d.region = 'North America'" else ""}
                                 ${if (plan) "AND a.plan = 'Team'" else ""}) AS value
                            FROM periods ORDER BY 1${if (plan) ", 2" else ""}
                        """.trimIndent()
                        compare(db, request(metric, groups, filters, "2026-07-15"), oracle)
                    }
                }
            }
        }
        // Joined dimensions can also be output group keys, with filters still inside the window.
        compare(db, request("active_accounts", listOf("metric_time__month", "account__region")), """
            WITH periods AS (
              SELECT date_trunc('month', date_day) bucket, MAX(date_day) endpoint
              FROM acme.time_spine_day WHERE date_day BETWEEN DATE '2025-10-13' AND DATE '2026-10-12' GROUP BY 1
            )
            SELECT CAST(bucket AS DATE), region, COUNT(DISTINCT a.account_id)
            FROM periods JOIN acme.daily_account_activity a ON a.activity_date BETWEEN endpoint - INTERVAL '6 days' AND endpoint
            JOIN acme.accounts d USING(account_id) WHERE active_users >= 1 GROUP BY 1, 2 ORDER BY 1, 2
        """.trimIndent())
        val active = rows(db, engine().explain(request("active_accounts", listOf("metric_time__month"))).sql)
        val core = rows(db, engine().explain(request("core_accounts", listOf("metric_time__month"))).sql)
        active.zip(core).forEach { (a, c) -> assertEquals(0.7, c.value / a.value, 0.000001) }
    }

    @Test
    fun `snapshot totals and pivot margins use last available date with all grouping combinations`() = database { db ->
        val total = compare(db, request("mrr"), "SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr WHERE month = DATE '2026-10-12'").single().value
        assertEquals(67547.54, total, 0.000001)
        assertEquals(679301.04, rows(db, "SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr").single().value, 0.000001)
        val regions = compare(db, request("mrr", listOf("account__region")), """
            SELECT region, SUM(mrr_usd) FROM acme.monthly_account_mrr JOIN acme.accounts USING(account_id)
            WHERE month = DATE '2026-10-12' GROUP BY 1 ORDER BY 1
        """.trimIndent())
        val months = compare(db, request("mrr", listOf("metric_time__month")), """
            SELECT CAST(date_trunc('month', month) AS DATE), SUM(mrr_usd) FROM acme.monthly_account_mrr GROUP BY 1 ORDER BY 1
        """.trimIndent())
        val cells = compare(db, request("mrr", listOf("account__region", "metric_time__month")), """
            SELECT region, CAST(date_trunc('month', month) AS DATE), SUM(mrr_usd)
            FROM acme.monthly_account_mrr JOIN acme.accounts USING(account_id) GROUP BY 1, 2 ORDER BY 1, 2
        """.trimIndent())
        assertEquals(total, regions.sumOf { it.value }, 0.000001)
        assertEquals(total, months.last().value, 0.000001)
        assertEquals(total, cells.filter { it.keys.last() == "2026-10-01" }.sumOf { it.value }, 0.000001)
        for (end in listOf("2026-10-11", "2026-09-12", "2026-09-30")) {
            compare(db, request("mrr", end = end), "SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr WHERE month = (SELECT MAX(month) FROM acme.monthly_account_mrr WHERE month <= DATE '$end')")
        }
        compare(db, request("mrr", filters = listOf("{{ TimeDimension('account_month__snapshot_date', 'day') }} <= DATE '2026-09-30'")),
            "SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr WHERE month = DATE '2026-09-12'")
        compare(db, request("mrr", filters = listOf("{{ Dimension('account__region') }} = 'Europe'")),
            "SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr WHERE month = DATE '2026-10-12' AND account_id = 11")
        // The filtered region has an older latest snapshot: filters must precede MAX selection.
        db.createStatement().use { it.execute("DELETE FROM acme.monthly_account_mrr WHERE account_id = 11 AND month = DATE '2026-10-12'") }
        compare(db, request("mrr", filters = listOf("{{ Dimension('account__region') }} = 'Europe'")),
            "SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr WHERE month = DATE '2026-09-12' AND account_id = 11")
    }

    @Test
    fun `joined cumulative windows exclude activity outside seven days and follow changing fact plans`() = database { db ->
        db.createStatement().use {
            it.execute("UPDATE acme.daily_account_activity SET active_users = CASE WHEN (dayofyear(activity_date) + account_id) % 13 = 0 THEN 1 ELSE 0 END, plan = CASE WHEN (dayofyear(activity_date) + account_id) % 2 = 0 THEN 'Team' ELSE 'Pro' END")
        }
        for (region in listOf(false, true)) {
            for (selectedPlan in listOf(false, true)) {
                val filters = buildList {
                    if (region) add("{{ Dimension('account__region') }} = 'North America'")
                    if (selectedPlan) add("{{ Dimension('account_day__plan') }} = 'Team'")
                }
                compare(db, request("active_accounts", listOf("metric_time__day", "account_day__plan"), filters, "2026-08-01", "2026-08-31"), """
                    SELECT date_day, a.plan, COUNT(DISTINCT a.account_id)
                    FROM acme.time_spine_day JOIN acme.daily_account_activity a
                    ON a.activity_date BETWEEN date_day - INTERVAL '6 days' AND date_day
                    JOIN acme.accounts d USING(account_id)
                    WHERE date_day BETWEEN DATE '2026-08-01' AND DATE '2026-08-31' AND active_users >= 1
                    ${if (region) "AND d.region = 'North America'" else ""}
                    ${if (selectedPlan) "AND a.plan = 'Team'" else ""}
                    GROUP BY 1, 2 ORDER BY 1, 2
                """.trimIndent())
            }
        }
    }

    @Test
    fun `paid accounts ARPA and retention components share the same selected snapshot`() = database { db ->
        // A new account contributes to MRR / paid accounts, but not the prior-paid cohort.
        db.createStatement().use {
            it.execute("INSERT INTO acme.monthly_account_mrr VALUES ('new', 12, DATE '2026-10-12', 5000, 0)")
        }
        val expressions = mapOf(
            "paid_accounts" to "SUM(CASE WHEN mrr_usd > 0 THEN 1 ELSE 0 END)",
            "arpa" to "SUM(mrr_usd) / SUM(CASE WHEN mrr_usd > 0 THEN 1 ELSE 0 END)",
            "prior_mrr" to "SUM(prior_mrr)", "retained" to "SUM(CASE WHEN prior_mrr > 0 THEN mrr_usd ELSE 0 END)",
            "capped" to "SUM(CASE WHEN prior_mrr > 0 THEN LEAST(mrr_usd, prior_mrr) ELSE 0 END)",
            "nrr" to "SUM(CASE WHEN prior_mrr > 0 THEN mrr_usd ELSE 0 END) / SUM(prior_mrr)",
            "grr" to "SUM(CASE WHEN prior_mrr > 0 THEN LEAST(mrr_usd, prior_mrr) ELSE 0 END) / SUM(prior_mrr)",
        )
        for ((metric, expression) in expressions) {
            for (groups in listOf(emptyList(), listOf("account__region"), listOf("metric_time__month"))) {
                val selectGroup = when (groups.firstOrNull()) {
                    "account__region" -> "region, "
                    "metric_time__month" -> "CAST(date_trunc('month', month) AS DATE), "
                    else -> ""
                }
                compare(db, request(metric, groups), "SELECT $selectGroup $expression FROM acme.monthly_account_mrr JOIN acme.accounts USING(account_id) " +
                    (if (groups == listOf("metric_time__month")) "" else "WHERE month = DATE '2026-10-12' ") +
                    (if (groups.isNotEmpty()) "GROUP BY 1 ORDER BY 1" else ""))
            }
        }
    }

    @Test
    fun `min max and entity window groupings select actual snapshots within each requested period`() = database { db ->
        for ((metric, choice) in listOf("per_account_first" to "MIN", "per_account_last" to "MAX")) {
            for (groups in listOf(emptyList(), listOf("metric_time__month"), listOf("account__region", "metric_time__month"), listOf("snapshot__snapshot_date__month"))) {
                val monthGroup = groups.any { it.endsWith("__month") }
                val regionGroup = "account__region" in groups
                val bucket = "CAST(date_trunc('month', snapshot_date) AS DATE)"
                val prefix = (if (regionGroup) "region, " else "") + (if (monthGroup) "$bucket, " else "")
                val count = groups.size
                val groupClause = if (count > 0) "GROUP BY ${(1..count).joinToString()} ORDER BY ${(1..count).joinToString()}" else ""
                compare(db, request(metric, groups), """
                    WITH selected AS (
                      SELECT account_id, ${if (monthGroup) "$bucket bucket," else ""} $choice(snapshot_date) chosen
                      FROM acme.irregular_snapshots GROUP BY account_id${if (monthGroup) ", 2" else ""}
                    )
                    SELECT $prefix SUM(value) FROM acme.irregular_snapshots s JOIN selected x
                    ON s.account_id = x.account_id AND s.snapshot_date = x.chosen
                    JOIN acme.accounts d ON s.account_id = d.account_id $groupClause
                """.trimIndent())
            }
        }
        compare(db, request("first"), "SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr WHERE month = DATE '2025-11-12'")
        compare(db, request("last_to_first"), "SELECT (SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr WHERE month = DATE '2026-10-12') / (SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr WHERE month = DATE '2025-11-12')")
        compare(db, request("last_to_all"), "SELECT (SELECT SUM(mrr_usd) FROM acme.monthly_account_mrr WHERE month = DATE '2026-10-12') / SUM(mrr_usd) FROM acme.monthly_account_mrr")
    }

    @Test
    fun `cumulative joins continue rejecting foreign to foreign fanout`() {
        val invalid = manifest.copy(semanticModels = manifest.semanticModels.map { model ->
            if (model.name == "accounts") model.copy(
                entities = model.entities.map { it.copy(type = EntityType.FOREIGN) } +
                    Entity("account_row", type = EntityType.PRIMARY, expr = "account_id"),
            ) else model
        })
        val failure = assertFailsWith<IllegalArgumentException> {
            engine(invalid).explain(request("active_accounts", listOf("metric_time__month", "account__region")))
        }
        assertTrue(failure.message.orEmpty().contains("region"), failure.message)
    }
}
