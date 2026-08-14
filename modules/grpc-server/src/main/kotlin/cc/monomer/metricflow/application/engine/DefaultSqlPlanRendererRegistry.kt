package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.databricks.DatabricksSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.duckdb.DuckDbSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.postgres.PostgresSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.redshift.RedshiftSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.snowflake.SnowflakeSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.trino.TrinoSqlPlanRenderer

/** Composition root for the complete renderer set exposed by the optional gRPC server. */
object DefaultSqlPlanRendererRegistry {
    /** Creates a registry containing every public renderer, including DuckDB. */
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
