# PHASE 5: API Surface Cleanup — 인수인계 문서

이 문서는 W12-W14 wave가 끝나고 corpus 100% PASS (또는 명시 quarantine으로 honest 완료) 상태에 도달한 뒤, **라이브러리답게 컨슈머 친화 구조로 재정리**하기 위한 새 에이전트용 인수인계다. 컨텍스트 0에서 받아 진행 가능하도록 자기완결형으로 작성됨.

## 현재 확정 상태 (2026-08-14)

Phase 5의 구조 정리와 초기 배포 경로가 완료됐다. `:engine`은 순수
in-process facade이며 gRPC/Netty/logback와 구체 renderer를 의존하지 않는다.
renderer는 `SqlPlanRendererRegistry`에 컨슈머가 명시적으로 등록한다.
protobuf/gRPC 서버와 wire adapter는 선택 모듈 `:grpc-server`로 분리됐다.

`./gradlew verifyMonomerProductBundle`는 `metricflow-core`, `metricflow-engine`과
BigQuery/Databricks/Postgres/Redshift/Snowflake/Trino renderer만 포함하는
결정적 Maven zip을 생성한다. DuckDB renderer와 `metricflow-grpc-server`는
public repository에는 남지만 Monomer product bundle에는 포함하지 않는다.
archive에는 `maven-repository/` 단일 root와 `.monomer-metricflow-manifest.json`이
포함된다. marker는 source/primary-JAR digest와 canonical artifact-set identity를
고정한다. CycloneDX/dependency/license/provenance evidence와 ZIP checksum은
별도 release asset이며, `.github/workflows/release.yml`은 pinned
`actions/attest`의 Sigstore JSON bundle과 attestation URL reference도 업로드한다.

## 0. 이 문서를 받은 너에게

먼저 읽기 (이 순서):
1. `HANDOFF.md` — 프로젝트 전체 맥락, 오케스트레이터 워크플로우
2. `CLAUDE.md` — 프로젝트 규약
3. `PROGRESS.md` — 진척 (W14 done 확인)
4. 이 문서 (PHASE_5_PLAN.md)

확인:
```bash
git log --oneline -3                  # W14 commit 있어야 함
./gradlew build                       # green
./gradlew :integration:diff-runner:run | grep -E "PASS|FAIL|UNIMPL"
# PASS=112 (또는 명시 quarantine 후 PASS+QUARANTINE=112)
```

이 조건이 안 되면 Phase 5 시작하지 말 것. W14 먼저 완료해야 함.

## 1. 왜 Phase 5가 필요한가

Phase 3 (W1-W14)는 **에이전트 병렬 포팅을 위한 33개 Gradle 모듈**로 구성됐다. Hexagonal architecture(`:domain`, `:infrastructure`, `:application`)에서 차용한 네이밍을 썼다.

**문제**: 이건 라이브러리에 잘못된 모델이다.
- metricflow는 순수 변환 라이브러리 (in-memory 데이터 → in-memory SQL)
- DB·네트워크·메시지큐 없음 → "ports/adapters" 분리할 I/O가 없음
- 33개 JAR + 라이브러리 컨슈머가 받는 의존성 그래프 = 비현실적
- 18 LOC / 23 LOC짜리 모듈 (`:infrastructure:sql:render:base/default`) = cargo cult
- `:application:engine`에 facade + gRPC 서버 섞임 → 컨슈머가 Netty 강제 의존
- `internal` 표시 부재 → 모든 헬퍼가 public API 표면

**목표**: 6-8개 의미 있는 JAR로 통합. 카테고리 오류 해소. Public API 표면 명확화.

## 2. 시작 상태 vs 목표 상태

### 현재 (Phase 3 끝)

```
33 Gradle modules:
:common:{toolkit,time,telemetry}                                        (3)
:domain:{manifest:{model,transformation,validation}, datatable,
         spec, spec:bind, lookup, semantic-graph, query, sqlclient,
         dataflow, metric-evaluation, plan-conversion,
         sql:{plan,optimizer,render}}                                  (16)
:infrastructure:sql:render:{base, default, trino, bigquery,
         snowflake, databricks, redshift, duckdb, postgres}             (9)
:application:engine                                                     (1)
:integration:diff-runner                                                (1)
                                                                = 30 leaf + intermediates
```

### 목표 (Phase 5 끝)

```
8 publishable artifacts + 1 internal-only:

core/                          # metricflow-core JAR — 모든 변환/검증/엔진 facade
render-base/                   # metricflow-render-base — dialect 인터페이스
render-default/                # metricflow-render-default — ANSI fallback
render-trino/                  # metricflow-render-trino
render-bigquery/               # metricflow-render-bigquery
render-snowflake/              # metricflow-render-snowflake
render-databricks/             # metricflow-render-databricks
render-redshift/               # metricflow-render-redshift
render-duckdb/                 # metricflow-render-duckdb
render-postgres/               # metricflow-render-postgres
grpc-server/                   # metricflow-grpc-server — 옵션 transport
test-fixtures/                 # metricflow-test-fixtures — 컨슈머 테스트용 (옵션)
internal-tests/                # 발행 안 함 — diff-runner 등 (was :integration:*)
```

총 발행 artifact 11개 + 1개 내부. 33 모듈에서 줄어듦. 각 JAR가 의미 있는 단위.

## 3. 무엇이 어디로 가는가 (이주 매핑)

### `core/` 모듈

현재 `:common:*`, `:domain:*`를 모두 흡수.

Kotlin 패키지는 **그대로 보존** (`cc.monomer.metricflow.{domain,common,…}`). Gradle 모듈만 합치고, 패키지 이름은 후속 작업에서. 이유:
- Python source path 추적성 유지
- import 수정 폭발 방지
- diff-runner regression 위험 최소화

이주 대상 (Gradle path → core 내부 위치):

| 현재 Gradle path | core 내부 폴더 |
|---|---|
| `:common:toolkit`              | `core/src/main/kotlin/com/.../common/util/` |
| `:common:time`                 | `core/src/main/kotlin/com/.../common/time/` |
| `:common:telemetry`            | `core/src/main/kotlin/com/.../common/telemetry/` (인터페이스만) |
| `:domain:manifest:model`       | `core/src/main/kotlin/com/.../domain/manifest/model/` |
| `:domain:manifest:transformation` | `core/src/main/kotlin/com/.../domain/manifest/transformation/` |
| `:domain:manifest:validation`  | `core/src/main/kotlin/com/.../domain/manifest/validation/` |
| `:domain:datatable`            | `core/src/main/kotlin/com/.../domain/datatable/` |
| `:domain:spec`                 | `core/src/main/kotlin/com/.../domain/spec/` |
| `:domain:spec:bind`            | `core/src/main/kotlin/com/.../domain/spec/bind/` |
| `:domain:lookup`               | `core/src/main/kotlin/com/.../domain/lookup/` |
| `:domain:semantic-graph`       | `core/src/main/kotlin/com/.../domain/semantic_graph/` |
| `:domain:query`                | `core/src/main/kotlin/com/.../domain/query/` |
| `:domain:sqlclient`            | **흡수**: `core/src/main/kotlin/com/.../domain/sql/render/` (SqlClient + SqlEngine) |
| `:domain:dataflow`             | `core/src/main/kotlin/com/.../domain/dataflow/` |
| `:domain:metric-evaluation`    | `core/src/main/kotlin/com/.../domain/metric_evaluation/` |
| `:domain:plan-conversion`      | `core/src/main/kotlin/com/.../domain/plan_conversion/` |
| `:domain:sql:plan`             | `core/src/main/kotlin/com/.../domain/sql/plan/` |
| `:domain:sql:optimizer`        | `core/src/main/kotlin/com/.../domain/sql/optimizer/` |
| `:domain:sql:render`           | `core/src/main/kotlin/com/.../domain/sql/render/` |
| `:application:engine` (facade만)| `core/src/main/kotlin/com/.../engine/` |

총 core JAR: 약 24,000 LOC, 모든 변환 로직 + 엔진 facade.

### `render-*/` 모듈들

`:infrastructure:sql:render:*` 9개 모듈을 그대로 옮기되 path만 변경:

| 현재 | 목표 |
|---|---|
| `:infrastructure:sql:render:base`     | `render-base/` |
| `:infrastructure:sql:render:default`  | `render-default/` |
| `:infrastructure:sql:render:trino`    | `render-trino/` |
| `:infrastructure:sql:render:bigquery` | `render-bigquery/` |
| (이하 동일 패턴) | |

각 JAR는 `core`를 의존. 컨슈머는 필요한 dialect만 선택.

Kotlin 패키지는 보존 (`cc.monomer.metricflow.infrastructure.sql.render.<dialect>`) — 또는 `cc.monomer.metricflow.render.<dialect>`로 옮길지는 별도 결정 (옵션 A: 그대로 두기 권장).

### `grpc-server/` 모듈

`:application:engine`에서 분리:
- **core로 남기는 것**: `MetricFlowEngine.kt`, `EngineModels.kt`, `MetricFlowEnginePort.kt`(있다면)
- **grpc-server로 옮기는 것**: `MetricFlowSqlEngineService.kt`, proto generated stubs, gRPC Netty server bootstrap, adapter/ 디렉토리 (proto ↔ domain 변환)

`grpc-server`는 `core`를 의존. gRPC 의존성(`io.grpc:grpc-netty-shaded`, protobuf-kotlin)은 이 모듈만 끌고 옴.

컨슈머가 라이브러리 모드로 쓰려면 `grpc-server` 의존 안 함.

### `test-fixtures/` 모듈 (옵션)

컨슈머 테스트용 헬퍼 (예: `simpleManifest()` 같은 fixture builder). Phase 5 시점에 명시 요청 없으면 **스킵** — 후속 작업.

### `internal-tests/` 모듈 (발행 안 함)

`:integration:diff-runner` 이동.

핵심: **이 모듈은 maven publish 대상이 아님.** `build.gradle.kts`에서 publish 제외 명시.

## 4. 구체 단계 (안전 우선)

각 단계 끝에 **diff-runner 통과 확인** 필수. 회귀 발견 시 그 단계만 revert.

### Step 0: 가드레일 설정

```bash
# Phase 5 시작 전 baseline 캡처
./gradlew :integration:diff-runner:run > /tmp/phase5-baseline.txt 2>&1
grep -E "PASS=|FAIL=|UNIMPLEMENTED=|ERROR=" /tmp/phase5-baseline.txt > /tmp/phase5-baseline-counts.txt
cat /tmp/phase5-baseline-counts.txt
# 예: PASS=112  FAIL=0  UNIMPLEMENTED=0  ERROR=0
```

이 baseline이 **회귀 검출 기준**. Phase 5 동안 매 단계 후 비교.

### Step 1: PUBLIC_API 명세

가장 먼저, 컨슈머가 임포트할 표면을 결정.

`docs/PUBLIC_API.md` 작성:
```markdown
# Public API Surface (metricflow-core JAR)

다음만 컨슈머가 임포트하는 stable surface다.

## Engine
- `cc.monomer.metricflow.engine.MetricFlowEngine` (facade)
- `cc.monomer.metricflow.engine.MetricFlowEngineRequest` 등 입출력 타입

## Data model (manifest)
- `cc.monomer.metricflow.domain.manifest.model.SemanticManifest`
- `cc.monomer.metricflow.domain.manifest.model.Metric` + 변형
- (목록 계속...)

## Specs
- `cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec`
- (목록 계속...)

## Render (인터페이스만)
- `cc.monomer.metricflow.domain.sql.render.SqlPlanRenderer`
- `cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderer`
- `cc.monomer.metricflow.domain.sqlclient.SqlEngine` (enum)

## NOT public
- 모든 `internal` 표기된 클래스
- `cc.monomer.metricflow.domain.dataflow.builder.*` (구현 디테일)
- `cc.monomer.metricflow.domain.plan_conversion.helpers.*` (구현 디테일)
- (목록 계속...)
```

이 문서가 Step 2의 가이드가 된다.

### Step 2: `internal` 가시성 강화

PUBLIC_API.md에 없는 모든 public top-level 타입에 `internal` modifier 추가.

전략:
1. 각 모듈을 grep으로 훑기: `find modules -name "*.kt" | xargs grep -l "^\(class\|object\|interface\|fun\|val\) " | xargs grep -L "^internal"`
2. PUBLIC_API.md와 대조해 internal로 표시할 것 결정
3. 단 builder 결과물 검증 (`./gradlew build` 그린 유지)

조심:
- `inline fun`은 `internal`로 하면 `@PublishedApi` 필요할 수 있음
- 같은 모듈 안에서 호출되는 헬퍼는 안전, 모듈 간 호출되는 헬퍼는 `internal`로 못 막음 (이게 모듈 통합 명분의 일부)

Step 2가 끝나야 Step 3 통합이 안전 (internal 표시된 게 통합 후에도 internal로 남음).

### Step 3: `:domain:sqlclient` (113 LOC) 흡수

가장 작고 안전한 첫 통합.

```bash
# 113 LOC 두 파일을 :domain:sql:render에 합치기
mv modules/domain/sqlclient/src/main/kotlin/cc/monomer/metricflow/domain/sqlclient/* \
   modules/domain/sql/render/src/main/kotlin/cc/monomer/metricflow/domain/sql/render/

# 패키지 선언 수정 (sqlclient → sql.render)
find modules/domain/sql/render -name "SqlClient.kt" -o -name "SqlEngine.kt" -o -name "SqlEngineTest.kt" | \
  xargs sed -i '' 's|package cc.monomer.metricflow.domain.sqlclient|package cc.monomer.metricflow.domain.sql.render|'

# 모든 import 수정
grep -rl "cc.monomer.metricflow.domain.sqlclient" modules/ --include="*.kt" --include="*.kts" | \
  xargs sed -i '' 's|cc.monomer.metricflow.domain.sqlclient|cc.monomer.metricflow.domain.sql.render|g'

# Gradle path 의존성 제거
grep -rl ":domain:sqlclient" modules/ --include="*.kts" | \
  xargs sed -i '' 's|implementation(project(":domain:sqlclient"))||; /^$/d'

# 모듈 제거
rm -rf modules/domain/sqlclient
sed -i '' '/module(":domain:sqlclient")/d' settings.gradle.kts

# 검증
./gradlew build && ./gradlew :integration:diff-runner:run | grep -E "PASS|FAIL"
# baseline과 동일해야 함
```

성공하면 commit. 실패하면 revert (`git reset --hard HEAD`).

### Step 4: 18-23 LOC dialect base 모듈 통합

`:infrastructure:sql:render:base` (23 LOC) + `:infrastructure:sql:render:default` (18 LOC)를 `:domain:sql:render`에 흡수.

Step 3와 동일한 절차. 핵심:
- `DialectSqlRenderingEngine` (base의 1개 클래스) → core/sql/render/
- `DefaultDialectSqlPlanRenderer` (default의 1개 클래스) → core/sql/render/
- 8개 dialect 모듈은 그대로 유지 (각자 의미 있는 단위)
- 8개 dialect의 build.gradle.kts 의존성: `:infrastructure:sql:render:base` → `:domain:sql:render`

Baseline 비교 후 commit/revert.

### Step 5: `:domain:*` → `core` 대통합

가장 큰 변경. 신중하게 단계 분할:

**Step 5a**: 새 `core/` 모듈 생성 (디렉토리 + 빈 build.gradle.kts + settings.gradle.kts에 추가)

**Step 5b**: `:common:toolkit` 흡수
- `mv modules/common/toolkit/src/main/kotlin/* modules/core/src/main/kotlin/`
- `mv modules/common/toolkit/src/test/kotlin/* modules/core/src/test/kotlin/`
- 모든 `:common:toolkit` 의존성 → `:core`
- README.md는 `core/docs/common-toolkit-README.md`로 이전 (기록 보존)
- 검증 → commit

**Step 5c-d-e-...**: 같은 절차로 나머지 `:common:*`, `:domain:*` 차례로 흡수
- 권장 순서 (leaf 먼저, 의존이 적은 것부터): `:common:toolkit`, `:common:time`, `:domain:datatable`, `:domain:manifest:model`, ..., `:domain:plan-conversion`, `:domain:metric-evaluation`, `:application:engine` (facade 부분만)
- 매 단계 baseline 비교 필수

**Step 5z**: 빈 `modules/common/`, `modules/domain/`, `modules/application/` 디렉토리 정리

이 흡수가 가장 위험. 한 번에 1-2 모듈만. **diff-runner regression 발견 시 즉시 revert.**

### Step 6: `grpc-server` 분리

`:application:engine` 중 gRPC 부분만 추출.

```
modules/grpc-server/
├── build.gradle.kts                       # gRPC 의존성 (Netty, protobuf-kotlin)
├── README.md
├── protos/                                # proto definitions (또는 core에서 참조)
└── src/main/kotlin/com/.../grpc/
    ├── MetricFlowSqlEngineService.kt      # was application/engine
    ├── adapter/                            # proto ↔ domain
    └── ServerBootstrap.kt
```

core는 gRPC를 안 알아야 한다. proto-generated 코드는 grpc-server에 머문다.

검증: `./gradlew :core:build` (gRPC 의존성 없음 확인), `./gradlew :grpc-server:run` (서버 기동), diff-runner.

### Step 7: `internal-tests/` 분리

`:integration:diff-runner` → `internal-tests/diff-runner/`.

publish 제외 명시:
```kotlin
// internal-tests/diff-runner/build.gradle.kts
plugins.withId("maven-publish") {
    // 이 모듈은 publish 안 함
    tasks.matching { it.name.startsWith("publish") }.configureEach {
        enabled = false
    }
}
```

또는 settings.gradle.kts에서 별도 includeBuild로 처리.

검증: `./gradlew :internal-tests:diff-runner:run` 동작.

### Step 8: 모듈 이름 일관화

이주 끝난 뒤 Gradle path 정리:
- 모두 dash 사용 (또는 모두 colon으로 nested)
- 권장: `:core`, `:render-trino`, `:render-bigquery`, ..., `:grpc-server`, `:internal-tests:diff-runner` — 평평한 dash 네이밍

### Step 9: 라이브러리 README 갱신

`README.md` (프로젝트 루트) — 컨슈머 시점 문서로 재작성:

```markdown
# metricflow-kotlin

dbt MetricFlow 의미층 엔진의 Kotlin/JVM 포팅. ...

## 사용

```kotlin
dependencies {
    implementation("cc.monomer.metricflow:metricflow-core:VERSION")
    implementation("cc.monomer.metricflow:metricflow-render-trino:VERSION")
    // 또는 다른 dialect
}

val engine = MetricFlowEngine.create(manifest)
val sql = engine.renderSql(...)
```

## Modules

| Module | Description |
|---|---|
| `metricflow-core` | 변환 + 검증 + 엔진 facade |
| `metricflow-render-trino` | Trino dialect |
| (이하) |

## gRPC server

옵션: `metricflow-grpc-server` 의존성 추가, ...
```

기존 CLAUDE.md / FEASIBILITY.md / HANDOFF.md / PHASE_5_PLAN.md 는 `docs/internal/`로 이전 권장 (개발자 문서).

### Step 10: 검증 + 차이 보고

최종:
- 33 모듈 → 11 모듈
- `./gradlew build` 그린
- diff-runner 결과 = baseline (회귀 없음)
- `find . -name "*.kt" | xargs grep -l "^internal " | wc -l` 증가
- maven publish 시 10 publishable + 1 internal-only; Monomer product bundle은 8 artifact를
  `maven-repository/` 단일 ZIP root 아래에 담는다.

## 5. 절대 하지 말 것

- **Kotlin 패키지 이름 대규모 변경 금지** — `cc.monomer.metricflow.domain.spec` 등 그대로. Gradle path만 변경.
- **알고리즘 로직 수정 금지** — Phase 5는 패키징만. 동작 변경 0.
- **도메인 어휘 변경 금지** — `Metric`, `LinkableSpec` 등 모두 그대로.
- **Python source 추적 잃지 말 것** — KDoc의 Python source 인용 유지.
- **corpus 통과율 떨어뜨리지 말 것** — baseline 동등성 필수.
- **diff-runner 모듈 publish 대상에 포함 금지** — 내부 도구.
- **테스트 fixture 무단 변경 금지** — 컨슈머 영향.

## 6. Acceptance Criteria (Phase 5 done 조건)

1. **모듈 수 ≤ 12** (이전 33)
2. **`./gradlew build` 그린**
3. **`:internal-tests:diff-runner:run` 결과 = baseline** (PASS/FAIL/UNIMPLEMENTED 동수)
4. **`docs/PUBLIC_API.md` 존재**, 컨슈머가 import할 표면을 정의
5. **`internal` 가시성 적용** — PUBLIC_API.md 외 타입 대부분 internal
6. **`:core` 모듈이 gRPC/Netty 의존 안 함** — 라이브러리 모드 가능
7. **`grpc-server` 모듈이 옵션 dependency** — 컨슈머가 선택
8. **dialect renderer는 각자 별도 JAR** — 컨슈머가 선택하고 product bundle은 외부 DW 6개만 포함
9. **`:integration:diff-runner` → `:internal-tests:diff-runner` 이전** — publishable 아님
10. **루트 `README.md`이 컨슈머 시점**으로 재작성
11. **Hexagonal 용어 (`:domain`, `:infrastructure`, `:application`) Gradle path에서 제거**
12. **PROGRESS.md에 "Phase 5 done" 표기 + 배포/릴리스 경로를 명시**

## 7. 단계별 commit 패턴

매 단계 (Step 3, 4, 5b, 5c, ...) 끝에 별도 commit. 메시지 패턴:

```
Phase 5 Step N: <변경 한 줄>

- <구체 변경>

diff-runner: PASS=X FAIL=0 UNIMPL=Y (baseline 동등)
누적: ~Z 모듈 (이전: W)
```

회귀 발견 시 그 단계만 revert (`git revert <SHA>`). Phase 5는 점진 작업이라 단계당 commit이 안전망.

## 8. Mission skill 적용 여부

Phase 5는 **알고리즘 작업이 아니라 패키징 작업**이라 mission skill (builder + evaluator)을 매 단계 돌리는 게 과한 면이 있다.

권장 워크플로우:
- **Step 1 (PUBLIC_API), Step 2 (internal 표시), Step 5 (core 통합)**: mission skill 사용. 변경 폭이 크고 회귀 위험 있음.
- **Step 3, 4, 6, 7, 8, 9, 10**: 직접 orchestrator가 진행 가능. mechanical refactor, diff-runner가 안전망.

직접 진행 시에도:
- 매 단계 baseline 비교
- 회귀 발견 → revert
- commit

## 9. 예상 비용

각 단계 약 100-200k 토큰 (mechanical refactor 위주).

- Step 1 PUBLIC_API: ~150k (Analysis-heavy)
- Step 2 internal 표시: ~200k (큰 sweep)
- Step 3-4 작은 통합: 각 ~50k
- Step 5 core 대통합 (a-z): ~500k (가장 큰 단계)
- Step 6 grpc-server 분리: ~100k
- Step 7-8-9-10: 각 ~50k
- 검증 cycle: ~100k

**총 ~1.5M 토큰** (4-7 mission cycle 또는 직접 + 검증 cycle).

## 10. 후속 작업 가능성

Phase 5 끝나도 추가 작업 가능:
- `test-fixtures/` 모듈: 컨슈머가 자기 테스트 작성할 때 쓸 fixture builder
- `metricflow-bom`: Gradle BOM (Bill of Materials) — 컨슈머가 버전 한 곳 관리
- Examples 추가: `examples/` 폴더에 단계별 query → SQL 예제
- KDoc → Dokka로 publish용 문서 생성
- CHANGELOG.md
- Maven Central publish 설정 및 signing/central-sync 운영 연결

이건 Phase 5 acceptance에 포함 안 시킴. Phase 5는 구조만.

## 11. 시작 명령

```bash
cd <repository-root>

# 1. baseline 캡처
./gradlew :integration:diff-runner:run > /tmp/phase5-baseline.txt 2>&1
echo "Baseline:" && grep -E "PASS=|FAIL=|UNIMPL=" /tmp/phase5-baseline.txt

# 2. 진척 추적용 텍스트 파일
cat > /tmp/phase5-progress.txt <<EOF
Phase 5 진행 상황
baseline: $(grep -E "PASS=|FAIL=|UNIMPL=" /tmp/phase5-baseline.txt | tr '\n' ' ')

[ ] Step 0: baseline
[ ] Step 1: PUBLIC_API.md
[ ] Step 2: internal sweep
[ ] Step 3: :domain:sqlclient 흡수
[ ] Step 4: 18-23 LOC base/default 흡수
[ ] Step 5: :domain:* → core 통합 (sub-step a-z)
[ ] Step 6: grpc-server 분리
[ ] Step 7: internal-tests 분리
[ ] Step 8: 모듈 이름 일관화
[ ] Step 9: README 컨슈머 시점 재작성
[ ] Step 10: Phase 5 done 검증
EOF

# 3. Step 1부터 시작
```

## 12. 마지막으로

- 이건 **알고리즘이 끝난 라이브러리**의 정리 작업이다. 정확성은 corpus diff-runner가 보장.
- Phase 5는 매 단계 안전망(diff-runner)이 있어 점진적·역행 가능.
- 한 번에 한 모듈씩, 매 단계 검증, 회귀 시 revert.
- 끝나면 **컨슈머가 mvn/Gradle 의존성으로 쓸 수 있는 라이브러리**가 된다.

성공.
