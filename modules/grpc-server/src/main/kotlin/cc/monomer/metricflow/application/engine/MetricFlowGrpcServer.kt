package cc.monomer.metricflow.application.engine

import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.protobuf.services.ProtoReflectionService
import org.slf4j.LoggerFactory

/**
 * Netty-backed gRPC server hosting [MetricFlowSqlEngineService].
 *
 * Configurable port via the `METRICFLOW_GRPC_PORT` environment variable;
 * default 10110 (matches the monomer-semantic-service consumer's expectation).
 *
 * Reflection is enabled so `grpcurl` / `evans` can probe the service during
 * Phase 3 development.
 */
class MetricFlowGrpcServer(
    private val port: Int,
    sqlPlanRendererRegistry: SqlPlanRendererRegistry,
) {

    private val server: Server = ServerBuilder
        .forPort(port)
        .addService(MetricFlowSqlEngineService(sqlPlanRendererRegistry))
        .addService(ProtoReflectionService.newInstance())
        .build()

    fun start() {
        server.start()
        log.info("MetricFlow gRPC server listening on port {}", port)
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Shutting down MetricFlow gRPC server")
            server.shutdown()
        })
    }

    fun awaitTermination() {
        server.awaitTermination()
    }

    companion object {
        private val log = LoggerFactory.getLogger(MetricFlowGrpcServer::class.java)
    }
}

fun main() {
    val port = System.getenv("METRICFLOW_GRPC_PORT")?.toIntOrNull() ?: DEFAULT_GRPC_PORT
    val server = MetricFlowGrpcServer(port, DefaultSqlPlanRendererRegistry.create())
    server.start()
    server.awaitTermination()
}

private const val DEFAULT_GRPC_PORT: Int = 10110
