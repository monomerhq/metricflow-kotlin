package cc.monomer.metricflow.integration.diff

import cc.monomer.metricflow.application.engine.SqlPlanRendererRegistration
import cc.monomer.metricflow.application.engine.SqlPlanRendererRegistry
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.databricks.DatabricksSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.duckdb.DuckDbSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.postgres.PostgresSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.redshift.RedshiftSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.snowflake.SnowflakeSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.trino.TrinoSqlPlanRenderer

/** Renderer composition used only by the full public differential corpus. */
internal object CorpusSqlPlanRendererRegistry {
    fun create(): SqlPlanRendererRegistry = SqlPlanRendererRegistry.of(
        SqlPlanRendererRegistration(SqlEngine.TRINO, TrinoSqlPlanRenderer()),
        SqlPlanRendererRegistration(SqlEngine.BIGQUERY, BigQuerySqlPlanRenderer()),
        SqlPlanRendererRegistration(SqlEngine.SNOWFLAKE, SnowflakeSqlPlanRenderer()),
        SqlPlanRendererRegistration(SqlEngine.DATABRICKS, DatabricksSqlPlanRenderer()),
        SqlPlanRendererRegistration(SqlEngine.REDSHIFT, RedshiftSqlPlanRenderer()),
        SqlPlanRendererRegistration(SqlEngine.DUCKDB, DuckDbSqlPlanRenderer()),
        SqlPlanRendererRegistration(SqlEngine.POSTGRES, PostgresSqlPlanRenderer()),
    )
}
