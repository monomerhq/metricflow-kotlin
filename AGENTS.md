# metricflow-kotlin

Public Kotlin port of MetricFlow's semantic query planning and SQL rendering engine.

## Identity

- Gradle group and package prefix: `cc.monomer.metricflow`
- Public proto package: `monomer.metricflow.v1`
- Public Java proto package: `cc.monomer.metricflow.protocol.v1`
- License: Apache-2.0; preserve dbt Labs and upstream attribution in `LICENSE` and `NOTICE`

## Boundaries

- The engine generates plans and SQL. It never connects to or executes against a warehouse.
- `python_oracle/upstream/` is immutable upstream reference material.
- Public modules are `core`, `engine`, and every `render-*` dialect module.
- `internal-diff-runner` is verification-only and must never be published.
- Do not add a product-service dependency or Monomer Control domain rules.
- Backward compatibility with the superseded private package namespace is not required.

## Verification

```bash
./gradlew test
./gradlew verifyPublicRepository
```

`verifyPublicRepository` must pass the 112-case differential corpus and produce POM, binary,
sources, and javadoc JARs under `build/maven-staging/` for every public module. Do not publish or
push without explicit approval.

## Git

Work on a branch/worktree, preserve unrelated changes, commit only a passing state, and do not
push unless the user explicitly asks.
