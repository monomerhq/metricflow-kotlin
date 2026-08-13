package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.application.engine.adapter.EngineProtoAdapter
import cc.monomer.metricflow.application.engine.adapter.ManifestEnvelopeAdapter
import cc.monomer.metricflow.protocol.v1.EntitiesForMetricsRequest
import cc.monomer.metricflow.protocol.v1.EntitiesForMetricsResponse
import cc.monomer.metricflow.protocol.v1.ExplainGetDimensionValuesRequest
import cc.monomer.metricflow.protocol.v1.ExplainGetDimensionValuesResponse
import cc.monomer.metricflow.protocol.v1.GroupBysOrderBy
import cc.monomer.metricflow.protocol.v1.ListDimensionsRequest
import cc.monomer.metricflow.protocol.v1.ListDimensionsResponse
import cc.monomer.metricflow.protocol.v1.ListGroupBysRequest
import cc.monomer.metricflow.protocol.v1.ListGroupBysResponse
import cc.monomer.metricflow.protocol.v1.ListMetricsRequest
import cc.monomer.metricflow.protocol.v1.ListMetricsResponse
import cc.monomer.metricflow.protocol.v1.ListSavedQueriesRequest
import cc.monomer.metricflow.protocol.v1.ListSavedQueriesResponse
import cc.monomer.metricflow.protocol.v1.MetricFlowSqlEngineGrpcKt
import cc.monomer.metricflow.protocol.v1.RenderSqlRequest
import cc.monomer.metricflow.protocol.v1.RenderSqlResponse
import cc.monomer.metricflow.protocol.v1.ValidateManifestRequest
import cc.monomer.metricflow.protocol.v1.ValidateManifestResponse
import io.grpc.Status
import io.grpc.StatusException

/**
 * gRPC service that exposes the eight `MetricFlowEngine` entry points.
 *
 * Each RPC builds a [MetricFlowEngine] from the request envelope and delegates
 * to the corresponding facade method. Deferred entry points (`RenderSql` and
 * `ExplainGetDimensionValues`) still return `UNIMPLEMENTED` per the W10 status
 * documented on [MetricFlowEngine].
 *
 * Exception mapping:
 * - [NotImplementedError] → `Status.UNIMPLEMENTED` with the engine's diagnostic.
 * - [IllegalArgumentException] / [kotlinx.serialization.SerializationException]
 *   → `Status.INVALID_ARGUMENT`.
 * - Anything else propagates as `Status.INTERNAL`.
 */
class MetricFlowSqlEngineService :
    MetricFlowSqlEngineGrpcKt.MetricFlowSqlEngineCoroutineImplBase() {

    override suspend fun renderSql(request: RenderSqlRequest): RenderSqlResponse =
        withMappedErrors("RenderSql") {
            val built = ManifestEnvelopeAdapter.build(request.manifest)
            val engine = MetricFlowEngine(built.manifest)
            val explainRequest = MetricFlowExplainRequest(
                metricNames = if (request.metricNamesCount > 0) request.metricNamesList else null,
                groupByNames = if (request.groupByNamesCount > 0) request.groupByNamesList else null,
                whereConstraints = if (request.whereConstraintsCount > 0) request.whereConstraintsList else null,
                orderByNames = if (request.orderByNamesCount > 0) request.orderByNamesList else null,
                limit = if (request.hasLimit()) request.limit else null,
                timeConstraintStart = if (request.hasTimeConstraintStart()) request.timeConstraintStart else null,
                timeConstraintEnd = if (request.hasTimeConstraintEnd()) request.timeConstraintEnd else null,
                savedQueryName = if (request.hasSavedQueryName()) request.savedQueryName else null,
                minMaxOnly = request.minMaxOnly,
                applyGroupBy = request.applyGroupBy,
                orderOutputColumnsByInputOrder = request.orderOutputColumnsByInputOrder,
                // gRPC contract has no dialect field today — default to DUCKDB (the ANSI-like
                // dialect we use for the engine's first-class rendering). W14c may add a
                // proto-level dialect field if the production caller needs per-dialect SQL.
                dialect = null,
            )
            val result = engine.explain(explainRequest)
            RenderSqlResponse.newBuilder().setSql(result.sql).build()
        }

    override suspend fun listMetrics(request: ListMetricsRequest): ListMetricsResponse =
        withMappedErrors("ListMetrics") {
            val built = ManifestEnvelopeAdapter.build(request.manifest)
            val engine = MetricFlowEngine(built.manifest)
            val metrics = engine.listMetrics(includeDimensions = request.includeDimensions)
            val responseBuilder = ListMetricsResponse.newBuilder()
            for (m in metrics) responseBuilder.addMetrics(EngineProtoAdapter.toProto(m))
            responseBuilder.build()
        }

    override suspend fun listDimensions(request: ListDimensionsRequest): ListDimensionsResponse =
        withMappedErrors("ListDimensions") {
            val built = ManifestEnvelopeAdapter.build(request.manifest)
            val engine = MetricFlowEngine(built.manifest)
            val metricNames = if (request.metricNamesCount > 0) request.metricNamesList else null
            val dims = engine.listDimensions(
                metricNames = metricNames,
                orderBy = GroupByOrderByAttribute.DUNDER_NAME,
            )
            val responseBuilder = ListDimensionsResponse.newBuilder()
            for (d in dims) responseBuilder.addDimensions(EngineProtoAdapter.toProto(d))
            responseBuilder.build()
        }

    override suspend fun entitiesForMetrics(request: EntitiesForMetricsRequest): EntitiesForMetricsResponse =
        withMappedErrors("EntitiesForMetrics") {
            val built = ManifestEnvelopeAdapter.build(request.manifest)
            val engine = MetricFlowEngine(built.manifest)
            val entities = engine.entitiesForMetrics(metricNames = request.metricNamesList)
            val responseBuilder = EntitiesForMetricsResponse.newBuilder()
            for (e in entities) responseBuilder.addEntities(EngineProtoAdapter.toProto(e))
            responseBuilder.build()
        }

    override suspend fun listGroupBys(request: ListGroupBysRequest): ListGroupBysResponse =
        withMappedErrors("ListGroupBys") {
            val built = ManifestEnvelopeAdapter.build(request.manifest)
            val engine = MetricFlowEngine(built.manifest)
            val metricNames = if (request.metricNamesCount > 0) request.metricNamesList else null
            val orderBy = when (request.orderBy) {
                GroupBysOrderBy.GROUP_BYS_ORDER_BY_SEMANTIC_MODEL_NAME -> GroupByOrderByAttribute.SEMANTIC_MODEL_NAME
                GroupBysOrderBy.GROUP_BYS_ORDER_BY_DUNDER_NAME,
                GroupBysOrderBy.GROUP_BYS_ORDER_BY_UNSPECIFIED,
                GroupBysOrderBy.UNRECOGNIZED -> GroupByOrderByAttribute.DUNDER_NAME
            }
            val listing = engine.listGroupBys(
                metricNames = metricNames,
                includeDerivedTimeGranularities = request.includeDerivedTimeGranularities,
                orderBy = orderBy,
            )
            val responseBuilder = ListGroupBysResponse.newBuilder()
            for (d in listing.dimensions) responseBuilder.addDimensions(EngineProtoAdapter.toProto(d))
            for (e in listing.entities) responseBuilder.addEntities(EngineProtoAdapter.toProto(e))
            responseBuilder.build()
        }

    override suspend fun listSavedQueries(request: ListSavedQueriesRequest): ListSavedQueriesResponse =
        withMappedErrors("ListSavedQueries") {
            val built = ManifestEnvelopeAdapter.build(request.manifest)
            val engine = MetricFlowEngine(built.manifest)
            val queries = engine.listSavedQueries()
            val responseBuilder = ListSavedQueriesResponse.newBuilder()
            for (q in queries) responseBuilder.addSavedQueries(EngineProtoAdapter.toProto(q))
            responseBuilder.build()
        }

    override suspend fun explainGetDimensionValues(
        request: ExplainGetDimensionValuesRequest,
    ): ExplainGetDimensionValuesResponse = withMappedErrors("ExplainGetDimensionValues") {
        val built = ManifestEnvelopeAdapter.build(request.manifest)
        val engine = MetricFlowEngine(built.manifest)
        val dimRequest = cc.monomer.metricflow.application.engine.ExplainGetDimensionValuesRequest(
            metricNames = request.metricNamesList,
            getGroupByValues = request.getGroupByValues,
            timeConstraintStart = if (request.hasTimeConstraintStart()) request.timeConstraintStart else null,
            timeConstraintEnd = if (request.hasTimeConstraintEnd()) request.timeConstraintEnd else null,
            minMaxOnly = request.minMaxOnly,
            dialect = null,
        )
        val result = engine.explainGetDimensionValues(dimRequest)
        ExplainGetDimensionValuesResponse.newBuilder().setSql(result.sql).build()
    }

    override suspend fun validateManifest(request: ValidateManifestRequest): ValidateManifestResponse =
        withMappedErrors("ValidateManifest") {
            val built = ManifestEnvelopeAdapter.build(request.manifest)
            val engine = MetricFlowEngine(built.manifest)
            val results = engine.validateManifest()
            val responseBuilder = ValidateManifestResponse.newBuilder()
            for (issue in results.allIssues) {
                responseBuilder.addIssues(EngineProtoAdapter.toProto(issue))
            }
            responseBuilder.errorCount = results.errors.size
            responseBuilder.futureErrorCount = results.futureErrors.size
            responseBuilder.warningCount = results.warnings.size
            responseBuilder.hasBlockingIssues = results.hasBlockingIssues
            responseBuilder.build()
        }

    /**
     * Wrap an RPC body so domain errors and W10 deferrals turn into the right
     * `Status` code. Keeps the per-RPC fan-out trivial.
     */
    private inline fun <T> withMappedErrors(rpcName: String, block: () -> T): T {
        return try {
            block()
        } catch (e: NotImplementedError) {
            throw StatusException(
                Status.UNIMPLEMENTED.withDescription(
                    "$rpcName not yet implemented: ${e.message ?: ""}",
                ).withCause(e),
            )
        } catch (e: kotlinx.serialization.SerializationException) {
            throw StatusException(
                Status.INVALID_ARGUMENT.withDescription(
                    "$rpcName failed to parse manifest: ${e.message ?: ""}",
                ).withCause(e),
            )
        } catch (e: IllegalArgumentException) {
            throw StatusException(
                Status.INVALID_ARGUMENT.withDescription(
                    "$rpcName: ${e.message ?: ""}",
                ).withCause(e),
            )
        } catch (e: StatusException) {
            throw e
        } catch (e: Throwable) {
            throw StatusException(
                Status.INTERNAL.withDescription(
                    "$rpcName failed: ${e::class.simpleName}: ${e.message ?: ""}",
                ).withCause(e),
            )
        }
    }
}
