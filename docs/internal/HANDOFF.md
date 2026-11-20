# HANDOFF — metricflow-kotlin 오케스트레이션 인수인계

이 문서는 새로운 Claude 에이전트가 이 프로젝트의 오케스트레이션을 이어받기 위한 **자기완결형 인수인계**다. 컨텍스트가 전혀 없는 상태에서도 이 문서 + 프로젝트 파일만으로 끝까지 진행 가능하도록 작성됨.

## 1. 너의 역할 (TL;DR)

너는 metricflow 0.210.0 Python → Kotlin/JVM 풀 포팅 프로젝트의 **오케스트레이터**다.

**핵심 원칙**:
- 너는 코드를 *직접 짜지 않는다*. `Agent` tool로 builder/evaluator 서브에이전트를 발사한다.
- `mission` skill 패턴: **Plan(너) → Build(서브에이전트) → Evaluate(별도 서브에이전트, fresh context) → Inline fix → Commit → 다음 wave**
- 서브에이전트는 이 대화의 컨텍스트를 못 본다. 각 dispatch prompt는 **완전 자기완결**이어야 한다.

오늘 날짜 추정: 2026-05-12 이후. 사용자 언어: 한국어.

## 2. 프로젝트 한 줄

dbt-labs/metricflow 0.210.0의 의미층 엔진을 Kotlin으로 **온전히** 포팅한다. **SQL 실행 로직만 제외**. 학습 가독성(KDoc/README/sealed type/도메인 어휘 보존)이 일등 시민.

세부 미션은 `CLAUDE.md`, `FEASIBILITY.md` 참고. 가장 먼저 읽을 것:
1. `CLAUDE.md` — 프로젝트 규약 (도메인 어휘 보존, kotlinx-serialization, default param 금지, KDoc/README 정책)
2. `FEASIBILITY.md` — 5-Phase 계획, 인터페이스 표면, 위험 요소
3. `PROGRESS.md` — 현재 진척 상태 (단일 진실의 원천)
4. `docs/guides/backend-conventions.md` — Kotlin 코딩 컨벤션
5. `docs/scope.md`, `docs/dependency-dag.md`, `docs/module-mapping.md`, `docs/data-model-mapping.md`, `docs/validation-rules-inventory.md`, `docs/domain-glossary-kotlin.md` — Phase 0 산출물

## 3. 현재 상태 (2026-05-12 시점)

### 진행
- **15 commits**, 누적 토큰 약 **7.1M**
- Phase 0 → Phase 1a/1b → Phase 2 → Phase 3 W1-W11 완료
- `./gradlew build` 그린, `./gradlew test` 그린 (659+ tests)
- 30+ Gradle 모듈, 약 16,000 Kotlin LOC

### Diff-runner 결과 (corpus 112 케이스)
```
PASS=81  FAIL=0  UNIMPLEMENTED=31  ERROR=0  TOTAL=112
```

per-RPC:
| RPC | PASS | 상태 |
|---|---|---|
| validate_manifest | 19/19 | ✅ 완료 |
| list_saved_queries | 17/17 | ✅ 완료 |
| list_dimensions | 19/19 | ✅ 완료 |
| list_metrics | 18/18 | ✅ 완료 |
| entities_for_metrics | 3/3 | ✅ 완료 |
| list_group_bys | 5/5 | ✅ 완료 |
| explain | 0/29 | ❌ UNIMPLEMENTED |
| explain_get_dimension_values | 0/2 | ❌ UNIMPLEMENTED |

### 남은 작업 (대략 3-4 wave)
**31 UNIMPLEMENTED는 모두 `explain` 경로**. 다음 forward dependencies가 필요:
- `metricflow/metric_evaluation/*` (18 files / 3,070 LOC) — 미포팅
- `metricflow/dataset/convert_semantic_model.py` (594 LOC) — 미포팅
- `metricflow/validation/dataflow_join_validator.py` (80 LOC) — 미포팅
- `DataflowNodeToSqlSubqueryVisitor` 23 visit 메서드 — W9c skeleton, body 미포팅
- `DataflowPlanBuilder.buildPlan` body — W9b skeleton, body 미포팅
- `MetricFlowQueryParser.parseAndValidateQuery` body — W8 skeleton, body 미포팅

PROGRESS.md의 "Next:" 라인을 확인해 다음 wave 결정.

## 4. 오케스트레이션 워크플로우 (매 wave마다 반복)

```
1. git log --oneline -5  # 현재 HEAD 확인
2. cat PROGRESS.md       # 진척 표 + "Next:" 라인 + 누적 토큰
3. ./gradlew build && ./gradlew :integration:diff-runner:run | tail -10  # 기준선 확인
4. Builder mission prompt 작성 → Agent tool로 발사 (general-purpose)
5. Builder 보고서 받음. 자가보고를 신뢰하지 말 것.
6. Evaluator mission prompt 작성 → Agent tool로 발사 (fresh context, skeptical)
7. Evaluator 보고서:
   - PASS이면: minor 이슈만 inline fix → 7번으로
   - FAIL이면: 다시 builder 발사 (max 3 cycle)
   - WARN이면: PASS로 간주하되 fix 권장
8. PROGRESS.md 갱신 (status, Next, 누적 토큰)
9. git add -A && git -c commit.gpgsign=false commit -q -m "$(cat <<EOF
   [wave 제목]
   
   [상세 본문 HEREDOC]
   EOF
   )"
10. 다음 wave로
```

### Builder dispatch 패턴

`Agent` tool, `subagent_type: "general-purpose"`, `isolation`은 사용하지 말 것 (worktree hooks 미설정 — 실패함).

prompt 템플릿:
```
You are a builder agent in a fire-and-forget mission. Self-contained prompt — no prior conversation context. Kotlin only. Do not commit.

# GOAL
[목표 한 문장 + 정량 기대치 (예: "27 FAIL → 0 FAIL")]

# WORKING DIRECTORY
`<repository-root>` — git repo, branch main. HEAD = [현재 SHA]

# READ FIRST
1. CLAUDE.md, PROGRESS.md, docs/guides/backend-conventions.md
2. [관련 모듈 README들]
3. [관련 Python 소스 경로들]

# PORT SCOPE
[정확히 무엇을 포팅할지]

# CRITICAL CONSTRAINTS
- DO NOT commit
- DO NOT modify python_oracle/, corpus/, harness/, docs/, PROGRESS.md, CLAUDE.md, FEASIBILITY.md, protos/
- May modify [구체 모듈 리스트]
- No default parameter values in production
- Module README + KDoc on every public top-level type
- Preserve domain vocabulary
- HONEST deferrals (NotImplementedError with forward-pointer); NEVER silent stubs
- Use kotlinx-serialization (Jackson 금지)

# DELIVERABLES
[파일 트리]

# PLAN
[단계별 가이드]

# REPORT FORMAT
[기대하는 보고서 섹션들]

Begin now.
```

### Evaluator dispatch 패턴

마찬가지로 `Agent` tool, `subagent_type: "general-purpose"`, fresh context. 회의적으로 검증.

prompt 템플릿:
```
Evaluator for [wave 이름] in `<repository-root>`. Skeptical verification.

# CONTEXT
[builder가 한 일 한 문단]

# ACCEPTANCE CRITERIA
1-15 정도 항목, 각자 verifiable

# VERIFY
```bash
[검증 명령들]
```

# OUTPUT FORMAT
[구조화된 보고서 양식]

Grading: PASS = N/N criteria + no regression + no commits. FAIL = ... WARN = ...

Begin now.
```

## 5. 프로젝트 규약 (절대 위반 금지)

CLAUDE.md, backend-conventions.md에서 추출한 핵심:

### 코드
- **Kotlin 2.3, JVM 25, kotlinx-serialization** (Jackson 금지)
- **production code에서 default parameter value 0건** (테스트, generated, 명시 문서화된 합성 facade는 면제)
- **도메인 어휘 보존**: `Metric` (not `MetricDefinition`), `LinkableSpec` (not `JoinableSpec`), `metric_time` → `MetricTime`. 자세한 매핑은 `docs/domain-glossary-kotlin.md`.
- **Pydantic 접두사 제거**: `PydanticMetric` → `Metric`
- **Identity equality + matches()** on visitor tree types (W4 SqlPlanNode, W9a DataflowPlanNode 패턴). 일반 데이터에는 `data class`.
- **Sealed interfaces** for closed sum types. 같은 sub-package 변형이 필요한 경우엔 `abstract class` + Visitor 패턴 (Kotlin 2 제약).
- **Value class** for ID-shaped 단일 필드 타입 (`MetricReference` 등).

### 모듈 / 파일
- **모든 public top-level type에 KDoc** — Python source path 인용 권장
- **모듈마다 README.md** — 역할, Python ↔ Kotlin 매핑 표, 설계 노트, deferred 항목
- **이름 보존 원칙**: 자작 이름으로 바꾸지 않는다. Python의 quirk (예: `PostgresSQLSqlPlanRenderer` 오타)는 명시적 결정 시에만 수정 (W6에서 했음, KDoc에 명시).

### 합격 정책 (`PROGRESS.md`에 정식 명시됨)
- **A. Acceptance bar**: strict 100% match after conservative semantic-preserving normalizer
  - 새 normalizer 규칙은 3건 이상 케이스에서 필요해야 추가
  - quarantine은 evaluator만 승인, 3개 카테고리만 허용 (Python 버그, dialect 비결정성, upstream 이슈)
- **B. 학습 가독성**: production 도메인 타입에 KDoc/README 누락 = FAIL
- **C. 비용**: 한도 없음 (rate limit이 throttle)

### 절대 손대지 말 것
- `python_oracle/upstream/` — read-only oracle
- `python_oracle/oracle/`, `python_oracle/cli.py`, `python_oracle/pyproject.toml` — W1a 계약, 안정
- `corpus/` — Phase 1b 자동 생성, 손대지 않기
- `harness/` — diff-runner 등 W2 자동화 (드물게 `harness/python_*.py` 추가 가능)
- `docs/` — Phase 0 산출물, 안정
- `protos/` — Phase 2 확정
- `PROGRESS.md`, `CLAUDE.md`, `FEASIBILITY.md` — orchestrator만 수정

### 절대 하지 말 것
- `git push` (사용자 명시 요청 없으면)
- `--no-verify`, `--amend` (force 작업)
- Silent stub (반환값을 가짜로 채우기). Honest deferral 필수: `throw NotImplementedError("...W12+ 의존: ...")`

## 6. 파일 위치 인덱스

```
<repository-root>/
├── CLAUDE.md, FEASIBILITY.md, PROGRESS.md, HANDOFF.md (이 문서)
├── docs/
│   ├── guides/backend-conventions.md       # Kotlin/Spring 컨벤션
│   ├── scope.md                            # 도달 가능 파일 60,346 LOC
│   ├── dependency-dag.md                   # 10 wave 의존 그래프
│   ├── module-mapping.md                   # Python → Kotlin 패키지 매핑
│   ├── data-model-mapping.md               # Pydantic → Kotlin 규칙 + 5 예제
│   ├── validation-rules-inventory.md       # 28 active rules
│   ├── domain-glossary-kotlin.md           # 이름 보존 정책 + 100+ 용어
│   └── scripts/reach.py                    # AST 트레이서 (재현 가능)
├── python_oracle/
│   ├── upstream/                           # metricflow 0.210.0 풀 소스 (read-only)
│   │   ├── metricflow/, metricflow_semantics/, metricflow_semantic_interfaces/
│   │   └── tests_metricflow/               # 2,854 SQL snapshot + 61 YAML fixture
│   ├── .venv/                              # uv venv, metricflow==0.210.0 (gitignored)
│   ├── cli.py + oracle/                    # 8개 RPC CLI (W1a)
│   └── engine_wrapper.py                   # monomer wrapper 사본 (참고용)
├── corpus/
│   ├── manifests/                          # 19 manifest JSON
│   ├── INDEX.md                            # 112 case 목록
│   └── <case-id>/                          # 112개 케이스 (request.json + expected/<dialect>.sql 또는 expected.json + meta.json)
├── harness/
│   ├── run_oracle.py                       # corpus 무결성 검사
│   ├── extract_corpus.py                   # 1회성 추출
│   ├── manifest_loader.py
│   ├── python_transform.py                 # Python parity 헬퍼 (W2a 추가)
│   ├── sql_norm/                           # SQL normalizer (3 rules)
│   └── reports/corpus_integrity.md         # 100% PASS (W1b 시점)
├── protos/
│   └── metricflow_sql_engine.proto         # 8 RPCs (W2에서 6 추가)
└── modules/                                # 30+ Gradle subprojects (W2 + W3-W11 채움)
    ├── common/{toolkit,time,telemetry}/
    ├── domain/
    │   ├── manifest/{model,transformation,validation}/
    │   ├── datatable/
    │   ├── spec/, spec/bind/
    │   ├── lookup/, semantic-graph/
    │   ├── query/, sqlclient/
    │   ├── sql/{plan,optimizer,render}/
    │   ├── dataflow/, plan-conversion/
    ├── infrastructure/sql/render/{base,default,trino,bigquery,snowflake,databricks,redshift,duckdb,postgres}/
    ├── application/engine/
    └── integration/diff-runner/
```

## 7. 환경 / 빌드

```bash
# JDK 25 (Homebrew openjdk 25.0.2 확인됨)
java -version    # openjdk 25.0.2 ...

# 빌드 + 테스트 (전체)
./gradlew build
./gradlew test

# Diff-runner (corpus 112 케이스 vs Kotlin 엔진 in-process)
./gradlew :integration:diff-runner:run

# Python oracle (8 RPC, JSON stdin/stdout)
python_oracle/.venv/bin/python python_oracle/cli.py validate_manifest < some.json

# 단일 모듈 테스트
./gradlew :domain:manifest:model:test
```

`./gradlew --version` → Gradle 9.0, JVM 25.0.2 확인.

## 8. 다음 wave 사양 (W12, W13, W14, W15)

PROGRESS.md "Next:" 라인 = **W12 metric_evaluation + converters**.

### W12: metric_evaluation + SemanticModelToDataSetConverter + JoinDataflowOutputValidator

**목표**: explain 경로의 forward 의존성을 모두 채워둔다 (3,744 LOC). 이 wave는 코드만 추가; corpus PASS 변화 없음.

**대상 Python 소스**:
- `python_oracle/upstream/metricflow/metric_evaluation/*.py` — 18 files / 3,070 LOC
  - `DepthFirstSearchMetricEvaluationPlanner`, `MetricEvaluationPlanner`, `PassThroughMetricEvaluationPlanner`
  - `me_plan` 데이터 모델
- `python_oracle/upstream/metricflow/dataset/convert_semantic_model.py` — 594 LOC
- `python_oracle/upstream/metricflow/validation/dataflow_join_validator.py` — 80 LOC

**Kotlin 타겟**:
- 새 모듈 `:domain:metric-evaluation` (또는 `:domain:dataflow` 확장) — 결정은 builder가
- `:domain:dataflow/dataset/SemanticModelToDataSetConverter.kt`
- `:domain:lookup/JoinDataflowOutputValidator.kt`

**acceptance**: `./gradlew build` 그린, 새 모듈 단위 테스트 통과, diff-runner 81/0/31/0 유지 (변화 없음).

### W13: DataflowNodeToSqlSubqueryVisitor 23 visit 메서드

**목표**: W9c에서 skeleton만 두고 모두 `NotImplementedError`인 23 visit 메서드의 body를 채운다 (~2,400 LOC).

**위치**: `modules/domain/plan-conversion/src/main/kotlin/com/.../plan_conversion/to_sql_plan/DataflowNodeToSqlSubqueryVisitor.kt`

**순서 권장** (간단한 leaf부터):
1. `visitReadSqlSourceNode` (leaf — `SemanticModelToDataSetConverter` 사용)
2. Linear passthrough: `visitConstrainTimeRangeNode`, `visitWhereFilterNode`, `visitOrderByLimitNode`, `visitSelectorNode`, `visitAliasSpecsNode`
3. Aggregations: `visitAggregateSimpleMetricInputsNode`, `visitComputeMetricsNode`, `visitCombineAggregatedOutputsNode`
4. Joins: `visitJoinOnEntitiesNode`, `visitJoinOverTimeRangeNode`, `visitJoinToTimeSpineNode`, `visitJoinToCustomGranularityNode`, `visitJoinConversionEventsNode`
5. 시간 변환: `visitMetricTimeDimensionTransformNode`, `visitOffsetCustomGranularityNode`, `visitOffsetBaseGrainByCustomGrainNode`
6. 그 외: `visitSemiAdditiveJoinNode`, `visitMinMaxNode`, `visitWindowReaggregationNode`, `visitAddGeneratedUuidColumnNode`, `visitWriteToResultDataTableNode`, `visitWriteToResultTableNode`

**acceptance**: 모든 visit 메서드가 NotImplementedError 없이 동작. unit test 추가. diff-runner의 explain 일부 PASS 시작 (builder body가 W14 후에 채워지면 본격적).

### W14: DataflowPlanBuilder.buildPlan + MetricFlowQueryParser.parseAndValidateQuery + 엔진 explain 연결

**목표**: 마지막 두 deferred body를 채우고 `MetricFlowEngine.explain`을 작동시킨다.

**위치**:
- `modules/domain/dataflow/src/main/kotlin/com/.../dataflow/builder/DataflowPlanBuilder.kt` (W9b skeleton의 buildPlan body)
- `modules/domain/query/src/main/kotlin/com/.../query/MetricFlowQueryParser.kt` (W8 skeleton의 parseAndValidateQuery body)
- `modules/application/engine/src/main/kotlin/com/.../application/engine/MetricFlowEngine.kt` (explain throws → 실제 작동)

**acceptance**: diff-runner explain corpus 다수 PASS. 목표 100+ / 112.

### W15: Corpus 폴리시 / 100% PASS 추구

**목표**: 남은 FAIL을 normalizer 추가 또는 quarantine으로 해소. 100% PASS 또는 명시 quarantine.

**전략**:
- `harness/sql_norm/rules/` 분석 — 어느 변형이 의미 보존 (whitespace, alias 순서 등)
- 3건 이상 케이스에서 필요한 변형만 normalizer로 추가
- 그 외는 quarantine.md로 명시 (3 카테고리 중 하나 정당화)

## 9. 핵심 함정 / 흔히 잘못 가는 곳

builder에게 항상 사전 경고하라:

1. **`metricflow/sql_clients/` 디렉토리는 0.210.0에 존재하지 않음** — `metricflow/protocols/sql_client.py`만 있음
2. **`metricflow/execution/`는 4 파일 중 3개가 explain에서 reachable** — Kotlin에서 `MetricFlowExplainResult` data class로 collapse (실행 task 자체는 안 만듦)
3. **`PostgresSQLSqlPlanRenderer` 오타** → Kotlin은 `PostgresSqlPlanRenderer`로 명시 수정 (W6)
4. **28 validation rules across 16 files** (FEASIBILITY가 처음 "11"이라고 한 건 오류)
5. **`CommonEntitysRule` 의도적 미포팅** — Python DEFAULT_RULES에 없음
6. **Pydantic v2** (msi_pydantic_shim 사용). `.model_dump()` 우선, `.dict()` v1 fallback.
7. **Identity equality, not data class** for visitor tree types
8. **Sealed restriction**: sub-package에 변형 있으면 `abstract class` + Visitor (Kotlin 2)
9. **`GroupByMetricSpec` equals/hash 의도적 불일치** — Python parity 유지 (KDoc 명시)
10. **2 minimal hand-written manifests skip differential parity** (`minimal_valid_manifest.json`, `minimal_invalid_manifest.json`) — W1 의도적 결정 (NodeRelation.relation_name + dsi_package_version Pydantic 자동 채움 미포팅)
11. **Worktree hooks 미설정** — `Agent` 호출 시 `isolation: "worktree"` 쓰지 말 것. 모든 wave는 sequential.
12. **`.claude/settings.json` 수정 차단**됨 (Self-Modification 보호). worktree 활성화 원하면 사용자에게 명시 권한 요청 필요.

## 10. Commit 메시지 패턴

매 wave commit은 HEREDOC으로:

```bash
git add -A && git -c commit.gpgsign=false commit -q -m "$(cat <<'EOF'
Phase 3 W12: metric_evaluation + dataset converter + join validator

[한 줄 요약 + 정량 변화 (예: "corpus 81/112 PASS 유지, forward deps 채움")]

[모듈별 변경 사항]
- modules/domain/metric-evaluation/ (또는 위치): ...
- modules/domain/dataflow/dataset/SemanticModelToDataSetConverter.kt
- modules/domain/lookup/JoinDataflowOutputValidator.kt

[설계 결정 / 보존된 quirk]
- ...

[검증]
- N tests / 0 failure
- 0 regression
- diff-runner: ...

[다음 wave에 deferred]
- ...

mission cycle 1 (pass on 1st eval, K inline fix), 누적 ~XM 토큰
EOF
)"
```

## 11. PROGRESS.md 갱신 규약

매 wave 끝에:

1. **진척 표 row 갱신**: `in-progress` → `done`, commit SHA 채우기 ("(pending commit)" → 실제 SHA)
2. **다음 row 갱신**: `not started` → `in-progress` + dispatched date
3. **"Next:"** 라인 갱신
4. **누적 토큰 추정** 라인 추가 (`builder ~X + evaluator ~Y + 오케스트레이션 ~Z = ~total`)
5. **누적 합계** 갱신

## 12. 마지막으로

- 너의 사용자는 한국어를 쓴다. 메시지 응답은 한국어로.
- 시점은 2026-05-12 이후로 가정 (날짜 자동 갱신).
- 사용자는 "끝까지 진행해"라고 명시했으므로 fire-and-forget. 중간에 사용자 명시 질문이 없으면 계속 진행.
- 사용자가 "비용한도는 넣지마. rate limit 걸려있어"라고 했으므로 토큰 한도 무시. 추정치 기록만.
- 평가자가 PASS주면 minor fix inline 후 commit. FAIL이면 builder 재발사 (max 3 cycle).
- 모든 missions이 끝나면 (corpus 100% PASS 또는 명시 quarantine으로 honest 완료) — 사용자에게 final 보고.

성공.
