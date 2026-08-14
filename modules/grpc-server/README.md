# `:grpc-server`

Optional transport capability for `metricflow-kotlin`. This module owns the
protobuf-generated contract, gRPC coroutine service, Netty server bootstrap, and
manifest/DTO wire adapters. It composes every public renderer, including
DuckDB, for compatibility with the complete public protocol surface.

Library consumers that call `MetricFlowEngine` directly should depend on
`metricflow-engine` plus only the `metricflow-render-*` modules they serve. The
Monomer product bundle intentionally excludes this module and the DuckDB
renderer.

Run the server with:

```bash
./gradlew :grpc-server:run
```
