# metricflow-kotlin

`AGENTS.md`가 현재 repository identity, publication, attribution 규칙의 정본이다.

MetricFlow의 의미층 엔진을 Kotlin/JVM으로 **온전히** 포팅한다.

## 미션 (두 줄)

1. **풀 포팅 (단 SQL 실행 로직 제외)**: dbt-labs/metricflow 0.210.0의 의미층 코어(`metricflow`, `metricflow_semantics`, `metricflow_semantic_interfaces`)를 Kotlin으로 옮긴다. 모든 dialect, 모든 public 진입점, 의미모델 검증 포함. **SQL 실행은 엔진의 책임이 아니므로** `MetricFlowEngine.query()`/`metricflow.execution/`/SqlClient 실행 메서드는 제외한다.
2. **읽히는 코드**: 사용자가 Kotlin 코드를 따라 읽으면서 의미층 엔진의 작동을 **완전히 이해할 수 있어야 한다.** 이게 산출물의 가치 절반이다.

## 포팅 대상 버전

- **metricflow 0.210.0** (2026-04-28, Apache-2.0)
- 0.210.0 변경: `dbt_semantic_interfaces` → **`metricflow_semantic_interfaces`** 패키지명. `MetricFlowEngine.get_measures_for_metrics` 제거(우리 영향 없음).

## 먼저 읽을 것

1. **[FEASIBILITY.md](FEASIBILITY.md)** — 범위, 위험, 5-Phase 계획, 합격 기준.
2. **[docs/guides/backend-conventions.md](docs/guides/backend-conventions.md)** — Kotlin/Spring 코딩 규약.
3. **[python_oracle/README.md](python_oracle/README.md)** — Python 측 oracle 위치와 진입점.
4. **[python_oracle/upstream/README.md](python_oracle/upstream/README.md), GLOSSARY.md, TENETS.md** — metricflow 자체의 도메인 어휘와 설계 의도. 코드 옮기기 전에 읽을 것.

## 엔진 인터페이스 표면 (풀 포팅, 단 SQL 실행 제외)

엔진은 의미층까지만 — **SQL 생성까지가 책임. SQL 실행은 엔진 외부.** 그래서 `MetricFlowEngine.query()`(실행 포함)와 `metricflow.execution/`, `SqlClient`의 실행 메서드(`query`/`execute`/`dry_run`), executor 구현체는 **포팅 대상에서 제외**한다.

| Kotlin 인터페이스 | 책임 |
|---|---|
| `explain(request)` | 쿼리 → 실행계획(`MetricFlowExplainResult`) + SQL 문자열. **SQL 실행 안 함** |
| `listMetrics(includeDimensions)` | 컴파일된 메트릭 카탈로그 |
| `listDimensions(metricNames?)` | 메트릭별/전체 dimension |
| `entitiesForMetrics(metricNames)` | 메트릭별 entity |
| `listGroupBys(metricNames)` | dimension + entity 통합 |
| `listSavedQueries()` | 저장된 쿼리 |
| `explainGetDimensionValues(...)` | dimension 값 조회 SQL (생성만, 실행 안 함) |
| `validateManifest(manifest)` | `SemanticManifestValidator` 기반 정합성 검사. 모든 검증 규칙 포팅 |

`SqlClient` 인터페이스는 “SQL plan을 어떤 dialect로 렌더할지”에 필요한 메타(예: `sqlEngineType`, `renderBindParameterKey`)만 포팅. 실행 메서드는 정의 자체를 두지 않는다(외부 시스템이 구현하지 않으므로). `metricflow.execution/` 패키지 전체 + `metricflow.sql_clients/`의 실행 어댑터 전체는 도달 불가 코드로 분류되어 자연스럽게 제외된다.

gRPC proto는 위 8개 인터페이스를 1:1로 노출.

## 학습 가독성 정책 (풀 포팅 일등 시민)

이 프로젝트는 “돌아가는 코드”뿐 아니라 “읽히는 코드”를 만든다. 모든 mission/PR에 다음을 강제:

1. **모듈마다 `README.md` (5–30줄)** — 이 모듈이 의미층의 어느 책임인지, 핵심 자료구조, 입력/출력 흐름. metricflow의 도메인 어휘를 그대로 쓴다 (예: `metric_time`, `linkable spec`, `semantic manifest`, `dataflow plan`).
2. **핵심 도메인 타입에 KDoc** — 의미적 정의, 불변식, 어떤 변환을 통해 만들어지는지. 단순 getter/setter엔 KDoc 안 쓴다.
3. **Sealed types로 sum type 명확화** — Pydantic의 `Union`/`Optional[Union]`은 Kotlin `sealed interface` + `data object`/`data class`로. enum-like는 `enum class`로.
4. **이름 보존** — Python의 도메인 용어를 Kotlin 명명에 그대로 보존 (`MetricTime`, `LinkableElementSet`, `SemanticManifest`, `DataflowPlan`). 임의로 “더 좋은 이름”으로 바꾸지 않는다. Python의 protocol/implementation 분리는 Kotlin `interface` + `class`로 매핑.
5. **직역 금지** — Pydantic 동적 검증이나 Python 특유 패턴은 Kotlin idiomatic으로 옮긴다. 의미는 보존하되 형태는 Kotlin다워야 한다.
6. **흐름 따라 읽히는 패키지 구조** — `domain/manifest`, `domain/spec`, `domain/dataflow`, `domain/sql`, `application/engine`, `infrastructure/sql/render/<dialect>` 식으로 **의미 흐름**을 따라 정렬. 알파벳/기능별이 아닌 의미층의 데이터 변환 단계로.

이 정책은 backend-conventions의 “주석 안 단다”와 충돌하는 것처럼 보이지만, **여기선 학습 목적이 우선**이라 KDoc/README는 권장된다. 단 “설명 없는 명백한 코드” 위에 동어반복 주석은 여전히 금지.

## HTML 설계/학습 문서 정책

설계 spec·research·교육 문서는 HTML로 작성한다. 매체 결정 규칙:

| 포맷 | 쓰는 경우 |
|---|---|
| **HTML** | 설계 spec·research·교육 문서. 100줄 넘어가는 문서. 다이어그램/표/하이라이트가 의미를 가짐. 사람이 읽고 의사결정에 쓰는 문서. 에이전트와 iterate하는 대상 |
| **Markdown** | 짧은 stable spec (<100줄). 인덱스·라우팅. 컨벤션·가이드. 코드 옆 README. 작업 메모. grep 대상 |

**판단 기준**: "다시 읽고 결정에 쓸 문서면 HTML, 기록·라우팅이면 MD".

가이드/템플릿:
- [docs/guides/html-docs-conventions.md](docs/guides/html-docs-conventions.md) — 디자인 토큰·레이아웃·컴포넌트·SVG 규칙 (monomer에서 import)
- [docs/guides/html-doc-template.html](docs/guides/html-doc-template.html) — 복사용 템플릿 (source of truth)

새 HTML 문서 만들 때:
1. 템플릿 복사 → 헤더 / TL;DR / 섹션 골격 갖춤
2. 텍스트 먼저, 다이어그램은 SVG로 (ASCII art 금지, PNG/JPG 금지)
3. `.refs` 컴포넌트로 코드 파일 경로 명시 — 독자가 drill-down 할 수 있게
4. Single accent `--clay` 절제 사용 (border-left / dot / underline / em italic만)

학습용 educational 문서는 `docs/explained/` 또는 `docs/guides/`에 둔다.

## 다른 핵심 규칙

- **Python을 oracle로 삼는다.** 합격 판정은 “tests pass”가 아니라 **“같은 입력에 대해 Python과 SQL/메타가 동등하다”**.
- **모든 dialect 포팅한다.** Trino, BigQuery, Snowflake, Databricks, Redshift, DuckDB, Postgres + Default. 각 dialect는 Phase 3의 별개 wave 모듈.
- **모듈 단위로 작업한다.** 한 PR = 한 모듈. wave 안에서만 병렬, wave 사이는 직렬.
- **upstream에 손대지 않는다.** `python_oracle/upstream/`는 read-only.
- **백포트 호환성 고려 안 함.** 가장 우아한 Kotlin 설계를 택한다.
- **default 파라미터 값 금지.** backend-conventions의 “Explicit Code” 규약 적용. 단 `MetricFlowQueryRequest`처럼 metricflow가 명시적으로 디폴트를 가진 진입점은 **Kotlin overload**로 흉내낸다 (call site는 명시 유지).
- **kotlinx-serialization 사용** (Jackson 아님).

## 파일 위치

```
metricflow-kotlin/
├── CLAUDE.md                    # 이 파일
├── FEASIBILITY.md               # 5-Phase 계획
├── docs/
│   ├── guides/backend-conventions.md   # 코딩 규약 (monomer에서 복사)
│   └── (Phase 0 산출물)
├── .claude/skills/
│   ├── evaluate/SKILL.md
│   └── mission/SKILL.md
├── python_oracle/
│   ├── upstream/                # metricflow 0.210.0 풀 소스 (학습/oracle/corpus, read-only)
│   │   ├── README.md, GLOSSARY.md, TENETS.md, AGENTS.md
│   │   ├── metricflow/, metricflow_semantics/, metricflow_semantic_interfaces/
│   │   └── tests_metricflow/    # 2,854 SQL snapshots, 61 YAML fixtures (corpus 1차 자료)
│   ├── engine_wrapper.py        # monomer wrapper 사본 (참고용)
│   └── README.md
├── protos/
│   └── metricflow_sql_engine.proto  # monomer 운영 시점 contract. 풀 포팅 시 확장 필요
├── harness/                     # Phase 1: oracle runner + diff_runner + sql normalizer
├── corpus/                      # Phase 1: tests_metricflow에서 추출한 corpus + 운영 trace
├── src/                         # Phase 2부터: Kotlin 소스
└── PROGRESS.md                  # 모든 에이전트의 단일 진척 파일
```

## 진척 추적 (PROGRESS.md)

`PROGRESS.md`(루트)가 단일 진실의 원천. **모든 에이전트는**:

1. 시작 시 `PROGRESS.md`를 읽는다.
2. 자기 작업 항목을 “in-progress”로 표시한다.
3. 완료 시 “done”으로 표시하고 다음 행동을 적는다.

## 합격 판정 (mission/evaluate skill)

- **`mission`** ([.claude/skills/mission/SKILL.md](.claude/skills/mission/SKILL.md)) — Plan → Build (worktree subagent) → Evaluate (별도 subagent) → 최대 3회 재시도.
- **`evaluate`** ([.claude/skills/evaluate/SKILL.md](.claude/skills/evaluate/SKILL.md)) — `git diff --stat`이 진실의 원천.

**이 프로젝트에 특화된 evaluator 항목 (Phase 2 이후 모듈 PR)**:

- 단위 테스트 통과 (`./gradlew :module:test`)
- **diff_runner 통과** — `harness/run_diff.sh --module=<name>` 등이 corpus 100% 일치 보고
- backend-conventions 준수 (default 파라미터 값 0건, 네이밍, 헥사고날, kotlinx-serialization)
- **학습 가독성 항목** (이 프로젝트 추가):
  - 모듈 README.md 존재
  - 핵심 도메인 타입에 KDoc
  - 패키지 구조가 의미 흐름을 따름
  - Python 도메인 용어 보존
- `python_oracle/upstream/` 수정 0건

빌드 인프라 없는 Phase 0/1 작업은 “산출 문서가 사실에 부합하는가”로 합격 판정.

## Corpus 전략

차등 테스트 자료 두 종류:

1. **1차 자료 — `tests_metricflow/snapshots/`** (2,854 SQL snapshots): metricflow 자체의 query→SQL 회귀 테스트. 입력 fixture(`tests_metricflow/fixtures/`)와 expected SQL이 매칭. **풀 표면(모든 dialect, 메트릭 유형, 엣지 케이스)을 이미 커버.** Phase 1에서 이걸 corpus로 표준화.
2. **2차 자료 — Monomer Control `semantic` 모듈 운영 trace** (선택): Monomer 실제 사용 패턴. 1차로 부족한 부분 보강용. 우선순위 낮음.

운영 corpus가 없어도 1차 자료로 풀 포팅 검증 가능. 사용자 의존 critical path가 사라짐.

## 빌드/실행

아직 Gradle 프로젝트 아님. **Phase 2의 첫 task가 scaffolding.**

Phase 2 이후 예상:
```
./gradlew build
./gradlew test
./gradlew integrationTest      # diff_runner 포함
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun   # gRPC 서버 (선택)
```

gRPC 서버는 풀 포팅의 부산물이다. Monomer Control은 versioned Maven artifact를
in-process로 사용하며 이 repository는 제품 orchestration을 소유하지 않는다.

## 절대 하지 말 것

- 외부에 push (사용자 명시 요청 없이는 push 금지)
- `python_oracle/upstream/` 수정
- 한 번에 여러 모듈 묶음 PR (모듈 단위 PR 원칙)
- 모듈 README, KDoc 누락한 채로 PR
- “일치율 95%”로 모듈 합격 처리 (목표는 100%, 예외는 normalizer로 흡수하고 명시 기록)
- corpus가 없는 상태에서 Phase 3로 진입
- “더 좋은 이름” 핑계로 metricflow 도메인 용어 변경
