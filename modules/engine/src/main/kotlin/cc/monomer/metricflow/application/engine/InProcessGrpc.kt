package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.protocol.v1.MetricFlowSqlEngineGrpcKt
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.TimeUnit

/**
 * Helper for running the engine inside the same JVM without a TCP socket.
 *
 * Used by `:integration:diff-runner` so the diff loop exercises the real gRPC
 * service stack (serialization, status mapping) without paying for network IO.
 * Real callers (e.g. monomer-semantic-service) talk to [MetricFlowGrpcServer]
 * over Netty.
 */
class InProcessEngine private constructor(
    private val server: Server,
    val channel: ManagedChannel,
) : AutoCloseable {

    val client: MetricFlowSqlEngineGrpcKt.MetricFlowSqlEngineCoroutineStub =
        MetricFlowSqlEngineGrpcKt.MetricFlowSqlEngineCoroutineStub(channel)

    override fun close() {
        channel.shutdown().awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        server.shutdown().awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_SECONDS: Long = 5

        fun start(): InProcessEngine {
            val serverName = InProcessServerBuilder.generateName()
            val server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(MetricFlowSqlEngineService())
                .build()
                .start()
            val channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build()
            return InProcessEngine(server, channel)
        }
    }
}
