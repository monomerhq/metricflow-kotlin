# metricflow-kotlin: 타당성 검토 + 에이전트 실행 전략

작성일: 2026-05-09 / 최종 갱신: 2026-05-09 (풀 포팅 결정 반영)
대상 독자: 프로젝트를 fire-and-forget으로 에이전트에 위임하려는 팀 리드

## 결론 한 줄

**가능. metricflow 0.210.0 의미층 코어를 풀 포팅하는 약 18–24 mission-week 규모의 프로젝트로, 차등 테스트(differential testing)가 자기검증 루프를 제공하므로 fire-and-forget이 성립한다.**

학습 목적(사용자가 Kotlin 코드를 읽으며 의미층을 이해)이 있으므로 “돌아가는 코드”뿐 아니라 “읽히는 코드”가 합격 기준.

---

## 1. 사용 표면 (풀 포팅, 단 SQL 실행 제외)

엔진은 **의미층까지만 — SQL 생성까지** 책임. SQL 실행은 엔진의 책임이 아니므로 `MetricFlowEngine.query()`(실행 포함), `SqlClient`의 실행 메서드(`query`/`execute`/`dry_run`), executor 구현체(`metricflow/execution/executor.py` 1개 파일/89 LOC)는 **포팅 대상에서 제외**한다. 단 `metricflow/execution/`의 나머지 3개 파일(`convert_to_execution_plan.py`, `dataflow_to_execution.py`, `execution_plan.py`)은 `MetricFlowEngine.explain()`에서 도달 가능 — SQL 결과를 task로 감싸는 데이터 구조 역할만 한다. Kotlin에서는 작은 `MetricFlowExplainResult` 데이터 클래스로 합친다 (Phase 0 결과). `SqlClient`는 "렌더에 필요한 메타"(`sqlEngineType`, `renderBindParameterKey`)만 인터페이스로 남긴다. 0.210.0에서 `metricflow/sql_clients/` 디렉토리는 존재하지 않음(테스트 전용 사본만 있음).

포팅 대상 — `MetricFlowEngine`의 SQL-생성 진입점 + 의미모델 검증:

| Kotlin 인터페이스 | Python 진입점 | 비고 |
|---|---|---|
| `explain(request)` | `MetricFlowEngine.explain` | 핵심: 쿼리 → 실행계획 + SQL. **실행 안 함** |
| `listMetrics(includeDimensions)` | `list_metrics` | 메트릭 카탈로그 |
| `listDimensions(metricNames?)` | `list_dimensions` | metricNames=null이면 전체 |
| `entitiesForMetrics(metricNames)` | `entities_for_metrics` | |
| `listGroupBys(metricNames)` | `list_group_bys` | dimension+entity 통합 |
| `listSavedQueries()` | `list_saved_queries` | |
| `explainGetDimensionValues(...)` | `explain_get_dimension_values` | dimension 값 조회 SQL 생성만 |
| `validateManifest(manifest)` | `SemanticManifestValidator.checked_validations` | **28개 검증 규칙 (16개 파일)** — Phase 0에서 정정 |

Dialect: **모두 포팅**. Trino, BigQuery, Snowflake, Databricks, Redshift, DuckDB, Postgres + Default. 각 dialect는 Phase 3의 별개 wave 모듈.

gRPC proto는 위 8개 인터페이스를 1:1 노출하는 형태로 확장. 현재 `protos/metricflow_sql_engine.proto`는 monomer 운영 시점의 부분집합이므로 Phase 2 scaffolding에서 풀 표면으로 확장.

## 2. 포팅 대상 코드 규모

metricflow **0.210.0** 풀 소스(테스트 제외):

| 패키지 | 파일 수 | LOC |
|---|---|---|
| `metricflow` | 148 | 26,009 |
| `metricflow_semantics` | 306 | 28,681 |
| `metricflow_semantic_interfaces` | 120 | 12,976 |
| **합계** | **574** | **67,666** |

Kotlin은 데이터 클래스/타입에서 더 장황한 경향(Pydantic 대비)이라 1.3~1.7배 LOC를 잡으면 **88k–115k LOC** 예상.

**Phase 0 측정 결과 (`docs/scope.md`)**: 도달 가능 **60,346 LOC / 477 files** (89%), 실행 제외 1 file 89 LOC, 그 외 도달 불가 96 files 7,231 LOC. Kotlin 환산 **약 78k–103k LOC**. 검증 모듈은 28개 규칙 (16개 파일).

## 3. 위험 요소

| 위험 | 영향 | 완화책 |
|---|---|---|
| SQL 생성 미세 차이 (공백, alias 순서, 식 동등 변형) | 통합 회귀 | normalizer 또는 의미적 동등성 비교 도구 |
| **모든 dialect 포팅 → 차이 표면 7배** | 모듈 wave 길어짐 | dialect별 별도 wave + 같은 corpus 재사용 |
| Pydantic의 자유로운 동적 검증/변환 | Kotlin에서 명시 모델로 옮길 때 누락 | `tests_metricflow_semantic_interfaces`로 확인 |
| 의존성 변환기/옵티마이저가 깊고 재귀적 | 포팅 중 잘못된 추상화 | leaf 우선 + tests_metricflow의 단위 fixture |
| **학습 가독성 vs 직역 유혹** | 직역하면 읽히지 않고, 자유 변형하면 의미 어긋남 | 도메인 용어 강제 보존 + sealed type 강제 + 모듈 README/KDoc 강제 |
| upstream metricflow 변경 추격 | 장기 유지보수 부담 | 0.210.0에 핀, 마이너 릴리즈 단위 sync mission |
| Kotlin 측 SQL 빌더 선택 실수 | 후반부 큰 비용 | metricflow의 plan→render 구조 그대로 옮김. jOOQ/Calcite로 대체하지 않음 |

## 4. 가용한 자산 (큰 호재 1개)

- **Apache-2.0**: 합법적 포팅 + 재배포 OK
- **`tests_metricflow/`가 corpus의 1차 자료** ✦
  - **2,854개 SQL snapshot** (`upstream/tests_metricflow/snapshots/`)
  - **61개 YAML fixture** (`upstream/tests_metricflow/fixtures/`)
  - 213개 테스트 파일 (입력 → expected 매핑)
  - metricflow 팀이 검증한 = 신뢰 가능한 ground truth, 모든 dialect/메트릭 유형/엣지케이스 커버
  - **이로 인해 monomer-semantic-service 운영 trace 캡처가 critical path에서 빠진다.** 사용자 의존이 줄어듬.
- **gRPC contract 초안 존재**: `protos/metricflow_sql_engine.proto` (운영 시점, 풀 포팅 시 확장)
- **학습/도메인 자료가 풀 소스에 있음**: README.md, GLOSSARY.md, TENETS.md, AGENTS.md, CHANGELOG.md, CONTRIBUTING.md (`python_oracle/upstream/` 루트)

---

## 5. 권장 전략: 5-Phase Differential-Tested Port

전제: 각 단계 완료 시 한 번 사용자 확인. 단계 내부는 에이전트 자율.

### Phase 0 — Recon (단일 mission)

산출물 (`docs/`):
- `scope.md` — 풀 포팅 범위에서 도달 가능 vs 불가능 .py 파일 목록과 LOC. dialect 7개 모두 포함 시.
- `dependency-dag.md` — 모듈 의존성 DAG (Mermaid). leaf-first, wave 분할 제안.
- `module-mapping.md` — Python 모듈 → Kotlin 패키지 매핑. 의미 흐름 따라 (`domain/manifest`, `domain/spec`, `domain/dataflow`, `domain/sql`, `application/engine`, `infrastructure/sql/render/<dialect>`).
- `data-model-mapping.md` — Pydantic → Kotlin 매핑 원칙. value class for IDs, sealed for unions, kotlinx-serialization, protocol → interface.
- `validation-rules-inventory.md` — `metricflow_semantic_interfaces/validations/` 규칙 11개 인벤토리와 각자의 검증 책임.
- `domain-glossary-kotlin.md` — metricflow GLOSSARY.md를 Kotlin 명명에 맞춘 매핑 (이름 보존).

수용 기준: 모든 문서가 실제 파일 경로와 LOC를 인용. 재현 가능 명령 포함. **Kotlin 코드 작성 0줄.**

### Phase 1 — Differential Test Harness (단일 mission)

산출물:
- `harness/python_oracle/` — Python 진입점들을 함수 호출로 노출 (in-process 또는 gRPC). 모든 9개 인터페이스 + ValidateManifest.
- `corpus/upstream/` — `tests_metricflow/` 의 입력/expected를 표준 corpus 포맷으로 변환. **2,854 케이스 임포트** 목표. dialect별 인덱스.
- `harness/diff_runner/` — Kotlin이 같은 입력에 대해 같은 결과(SQL/메타/검증 issue)를 내는지 비교. 메서드별 비교기.
- `harness/sql_norm/` — 의미 보존 normalizer. 처음엔 strict, 차차 약화.
- (선택) `corpus/monomer_trace/` — monomer-semantic-service 운영 trace. 1차 corpus가 충분하므로 우선순위 낮음.

수용 기준: corpus 케이스 N건 임포트, Python oracle 단독 실행 시 모두 expected와 일치. Kotlin 측 비어있음 → 100% 불일치가 정상 출력.

### Phase 2 — Scaffolding (단일 mission)

산출물:
- Gradle 프로젝트 (`build.gradle.kts`, `settings.gradle.kts`) — JVM 25, Kotlin 2.3, gRPC, kotlinx-serialization, kotlinx-coroutines (semantic-service와 동일 스택)
- 멀티모듈 layout (의미 흐름 따라): `:domain:manifest`, `:domain:spec`, `:domain:dataflow`, `:domain:sql`, `:application:engine`, `:infrastructure:sql:render:trino` … 등.
- `protos/` 풀 표면으로 확장: 9개 RPC 모두 + `ValidateManifest`
- 빈 gRPC 서버 stub (`UNIMPLEMENTED`)
- 첫 통합 테스트: diff_runner가 0건 일치, N건 불일치를 정상 보고

수용 기준: `./gradlew build` 통과, gRPC 서버 기동 OK, harness 연결 OK.

### Phase 3 — Module Ports (병렬 mission, 가장 큰 단계)

Phase 0의 의존성 DAG를 wave로 자른다. 한 wave 내 모듈은 서로 의존하지 않으므로 병렬.

**Phase 0 확정 — 10 waves** (자세한 내용 `docs/dependency-dag.md`):
- W1 — `domain.manifest.model` (70 files, 5,122 LOC). 모든 다른 wave가 의존
- W2 (병렬) — `domain.manifest.transformation` ‖ `domain.manifest.validation` (38, 6,090). 검증 28개 규칙 + 변환 14개 규칙
- W3 (4-way 병렬) — `common.toolkit` ‖ `common.telemetry` ‖ `common.time` ‖ `domain.datatable` (78, 6,251)
- W4 (병렬) — `domain.spec.bind` ‖ `domain.sql.plan` (14, 2,960)
- W5 (병렬) — `domain.sql.optimizer` ‖ `domain.sql.render` (interface only) (12, 1,830)
- W6 — `infrastructure.sql.render.base` 후 **8-way 병렬** dialect renderers (11, 1,793)
- W7 — `domain.lookup` → `domain.semantic_graph` → `domain.spec` (직렬, 105 files, 11,563 LOC). 가장 큰 wave, 4–6 PR로 분할
- W8 (병렬) — `domain.query` ‖ `domain.sqlclient` (61, 6,083)
- W9 — `domain.dataflow` → `domain.plan_conversion` (직렬, 81 files, 17,057 LOC). 5–8 PR로 분할
- W10 — `application.engine` (7, 1,702). engine facade

기존 9-wave 안 대비 SQL plan 레이어가 query parser보다 더 leaf임이 의존성 추적에서 드러나 순서 변경됨 (Phase 0 발견).

각 모듈 합격 기준 (자동 검증 가능해야 함):
1. 단위 테스트 — 모듈 함수에 대해 Python을 oracle로 한 입출력 비교 fixture 통과
2. 누적 통합 테스트 — 그 wave까지 포팅된 코드만으로 corpus 일부에 대해 일치
3. 빌드 + 정적 분석 통과
4. **학습 가독성**: 모듈 README, 핵심 타입 KDoc, 도메인 용어 보존, sealed type 사용 (`evaluate` skill에 추가됨)

소요 추정: wave 9개, 모듈 60–90개. 단일 에이전트 직렬이면 8–12주, 병렬 4–6 에이전트라면 4–6주.

### Phase 4 — Integration & Examples (단일 mission)

- 모든 모듈 결합 후 corpus 100% match 달성 (모든 9개 메서드, 모든 8개 dialect)
- gRPC 서버 풀 표면 구현
- **학습용 예제 디렉토리** `examples/`: 메트릭 정의 → SQL 변환 과정을 단계별 코드로 보여주는 예제. dataflow plan → SQL plan → SQL 변환 시각화.
- 부하/지연 비교 (Kotlin 우위 확인)

수용 기준: corpus 100%, p95 지연 Python 대비 -50% 이상, examples 동작.

### Phase 5 — (선택) Cutover

이 프로젝트는 monomer-semantic-service cutover를 **강제하지 않는다**. 풀 포팅 자체가 가치. cutover는 별개 결정.

원할 경우:
- monomer-semantic-service의 gRPC client target 변경
- Shadow traffic 비교 후 전면 전환

---

## 6. Fire-and-forget을 *진짜* 가능하게 하는 4가지

1. **Differential corpus가 ground truth.** 합격선은 “tests pass”가 아니라 **“같은 입력에 대해 Python과 결과가 동등”**. `tests_metricflow/`의 2,854 snapshot이 그 자료.
2. **Worktree-per-module + mission skill.** 에이전트끼리 충돌 없음. main 머지는 evaluator 통과한 브랜치만.
3. **모듈 합격 기준이 한 줄 명령.** `./gradlew :module:check && ./harness diff --module=foo`로 합/불합 결정. 학습 가독성 항목은 evaluator의 정성 검사.
4. **상위 진척 파일(`PROGRESS.md`).** mission/parallel-agents 어느 쪽이든 이 파일이 조정자.

이 네 가지가 갖춰지면 사람이 매일 보지 않아도 진척이 망가지지 않는다.

---

## 7. 에이전트 실행 패턴

| 패턴 | 적합 단계 | 장점 | 단점 |
|---|---|---|---|
| **`mission` skill** (Plan → Build (worktree subagent) → Evaluate (별도 subagent), max 3 cycle) | 거의 모든 단계 | builder 자가 보고 신뢰 안 함, 평가자 분리, 자동 재시도 | 단순 단발 작업엔 과함 |
| **`/loop` dynamic** (단일 에이전트, 셀프 페이싱) | mission 끝나고 다음 작업으로 자동 전환할 때 | 단순 | 직렬 |
| **`superpowers:dispatching-parallel-agents`** (다수 mission 동시 발사) | Phase 3 wave 내 병렬화 | 빠름, 의존성 없는 모듈 동시 처리 | 조율 비용, 머지 충돌 가능 |
| **CronCreate / scheduled-tasks** | 장기 유지보수 (upstream sync) | 일정 예측 | 단발 포팅엔 비효율 |

이 프로젝트는 **mission skill이 핵심**. monomer에서 가져왔다. 두 skill의 핵심:

- builder 자가 보고는 검증 안 함. **`git diff --stat`**과 **실제 명령 실행**이 합격 판정.
- evaluator는 builder와 **컨텍스트 공유 안 한다** (별도 subagent, 같은 worktree).
- evaluator는 plan + acceptance criteria + backend-conventions.md를 받아 모든 항목을 수동 검증.
- 실패 시 evaluator의 구체 피드백을 그대로 다음 builder에 주입. 최대 3회.

**이 프로젝트에 특화된 evaluate 항목 (Phase 2 이후 모든 모듈 PR)**:

- 단위 테스트 통과
- **diff_runner 통과**: corpus 100% 일치 보고
- backend-conventions 준수 (default 파라미터 값 0건, 네이밍, 헥사고날, kotlinx-serialization)
- **학습 가독성**: 모듈 README 존재, 핵심 도메인 타입 KDoc, 도메인 용어 보존, sealed type 사용
- `python_oracle/upstream/` 수정 0건

권장 조합:

```
Phase 0  → /mission "Phase 0 Recon" (단일 사이클, evaluator가 6개 산출 문서 검증)
Phase 1  → /mission "Diff Harness + corpus from tests_metricflow"
Phase 2  → /mission "Scaffolding (multi-module)"
Phase 3  → wave마다: dispatching-parallel-agents로 모듈별 mission 병렬 발사
Phase 4  → /mission "Integration + examples"
Phase 5  → (선택, 사람 + 보조 에이전트)
```

---

## 8. 사용자가 미리 결정·준비해야 할 것 (단축됨)

풀 포팅 + 1차 corpus가 `tests_metricflow/`에 있어서 이전보다 결정 사항이 줄었다.

1. ~~upstream MetricFlow 버전 핀 결정~~ → **0.210.0 결정됨**
2. ~~Trino-only 정책~~ → **모든 dialect 포팅**
3. **Acceptance bar 정의** — “SQL 문자열 100% 일치” vs “의미적 동등” 중 후자라면 normalizer에 어떤 변형까지 허용할지 명세. metricflow 자체 snapshot 비교는 strict (자기 출력과 비교).
4. ~~실 입력 corpus 확보 경로~~ → **`tests_metricflow/`로 충분.** 운영 trace는 보조.
5. ~~운영 cutover 정책~~ → **이 프로젝트 범위 밖.** 별도 결정.
6. **에이전트 비용 한도** — 몇 회·몇 만 토큰까지 자율 사용 허용할지.
7. **학습 가독성 weight** — evaluator가 “모듈 README가 사실 동어반복”을 보면 FAIL을 줘야 하는지(엄격) 또는 WARN으로 그칠지(관대). 권장: 엄격.

---

## 9. 지금 당장 던질 수 있는 첫 fire-and-forget task

`/mission` skill로 호출.

```
/mission

GOAL:
metricflow-kotlin 프로젝트의 Phase 0 Recon을 완료한다.
풀 포팅 (모든 dialect, 모든 public 진입점, validate manifest 포함) 의 정확한 범위와
의존성 구조, 모듈 매핑을 정량으로 파악해 이후 단계의 작업 계획을 가능하게 한다.
이 프로젝트는 학습 목적도 있으므로 도메인 용어 보존과 흐름 따라 읽히는 패키지 구조가
산출물에 반영되어야 한다.

CONTEXT:
- Working dir: `<repository-root>`
- 먼저 읽기: CLAUDE.md, FEASIBILITY.md, python_oracle/README.md,
  python_oracle/upstream/{README.md, GLOSSARY.md, TENETS.md, AGENTS.md}
- Python source of truth: python_oracle/upstream/{metricflow, metricflow_semantics, metricflow_semantic_interfaces}
- 테스트 corpus 1차 자료: python_oracle/upstream/tests_metricflow/{snapshots, fixtures}
- 진입점 (풀 포팅 대상, SQL 실행 제외):
    MetricFlowEngine.{explain, list_metrics, list_dimensions,
                      entities_for_metrics, list_group_bys, list_saved_queries,
                      explain_get_dimension_values}
  + SemanticManifestValidator.checked_validations
- 명시 제외: MetricFlowEngine.query, metricflow.execution/, SqlClient의 실행 메서드
  (query/execute/dry_run), 모든 executor 구현체
- Dialect: 모두 (trino, bigquery, snowflake, databricks, redshift, duckdb, postgres, default)
- 수정 금지: python_oracle/upstream/

DONE WHEN (모든 항목 검증 가능):
1. docs/scope.md 존재 — 풀 포팅에서 도달 가능 vs 불가능 .py 파일 목록과 LOC, 재현 명령 포함. SQL 실행 관련 코드(metricflow.execution/, SqlClient 실행 메서드, executor 구현체)는 명시적으로 제외 분류
2. docs/dependency-dag.md 존재 — Mermaid DAG, leaf 우선, wave 9개 분할 제안
3. docs/module-mapping.md 존재 — Python 모듈 → Kotlin 패키지 매핑. 의미 흐름 따라 정렬 (domain/manifest, domain/spec, domain/dataflow, domain/sql, application/engine, infrastructure/sql/render/<dialect>)
4. docs/data-model-mapping.md 존재 — Pydantic → Kotlin 매핑 원칙 (kotlinx-serialization, value class, sealed interface, protocol → interface)
5. docs/validation-rules-inventory.md 존재 — metricflow_semantic_interfaces/validations/ 규칙 11개 인벤토리와 각자 책임
6. docs/domain-glossary-kotlin.md 존재 — metricflow GLOSSARY.md 어휘 → Kotlin 명명 매핑 (이름 보존 원칙)
7. 모든 LOC 수치는 `find python_oracle/upstream/<pkg> -name "*.py" | xargs wc -l`로 재현 가능
8. 어떤 Kotlin 코드도 작성되지 않음
9. python_oracle/upstream/ 디렉토리 수정 0건
```

이 mission이 끝나면 풀 포팅 비용을 정량으로 알 수 있고, Phase 1 mission(diff harness + corpus 임포트)의 spec을 정확히 쓸 수 있다.
