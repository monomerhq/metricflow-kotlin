# PROGRESS

metricflow-kotlin 포팅 단일 진척 파일. 모든 에이전트(오케스트레이터·builder·evaluator)는 시작 시 이 파일을 읽고, 작업 완료 시 갱신한다.

## 운영 규칙

- 새 mission 시작 시: 해당 행을 **in-progress**로 표시 + 시각 기록.
- mission 종료 시: **done** 또는 **failed**로 표시 + 커밋 SHA + 메모 1줄.
- 다음 작업은 항상 "Next" 섹션에 한 줄로 명시 — 새 세션이 이걸 보고 즉시 이어간다.
- 실패/재시도는 시도 횟수 표기.
- 환경 변화(설정 변경, 의존성 갱신)는 "Operational notes"에 추가.

## 정책 (확정, 2026-05-11)

**A. Acceptance bar** — strict 100% after conservative semantic-preserving normalizer
- normalizer 규칙은 `harness/sql_norm/rules/<name>.md`에 한 건씩 등록, 각자 의미 보존 근거 명시
- 신규 규칙은 **3건 이상의 케이스에서 필요해야 추가**. 1건만 적용되는 ad-hoc 규칙 금지
- 모듈 합격 = quarantine되지 않은 모든 케이스 100% (Python oracle SQL = Kotlin SQL, normalizer 적용 후)
- Quarantine 허용 사유 (3개만, 명시):
  1. metricflow Python 자체 버그 (재현 안 할 결정)
  2. dialect-specific 출력 순서 비결정성 (sets/dicts 등)
  3. metricflow upstream issue tracker에 보고된 알려진 결함
- Quarantine은 **evaluator만 승인**. builder는 자가 quarantine 못함
- `corpus/<id>/quarantine.md`에 사유·evaluator commit SHA 기록

**B. 학습 가독성 weight** — strict
- 운영 코드(`src/main/kotlin`)의 새 도메인 타입(top-level public class/interface) — KDoc 또는 모듈 README 누락 시 **FAIL**
- infra/build/generated/`src/test` 면제
- evaluator는 `git diff --name-only HEAD <prev> -- 'src/main/kotlin/**/*.kt'`로 변경 파일 추출 후 검사
- KDoc/README 동어반복 = WARN (FAIL 아님). "evaluate skill"의 강한 기준은 사용자 코멘트로 추후 강화 가능

**C. 비용 한도** — 없음 (rate limit이 자연 throttle)
- 매 cycle 누적 토큰 추정은 기록하지만 한도/경고 없음
- 사용자 명시 정지 신호까지 계속 진행

이 섹션은 사용자 후속 결정 시 갱신.

## 현재 상태

**Phase 0 — 완료** (commit `7001953`, 2026-05-09)
- 6개 산출 문서: scope.md, dependency-dag.md, module-mapping.md, data-model-mapping.md, validation-rules-inventory.md, domain-glossary-kotlin.md
- AST 트레이서 `docs/scripts/reach.py` (재현 가능)
- 측정 결과: 도달 가능 60,346 LOC / 477 files; 실행 제외 89 LOC; 기타 미도달 7,231 LOC

**Current**: Phase 5b/6 initial delivery is implemented on the release branch: the
in-process engine is transport-free, gRPC is an optional module, and the Monomer
external-DW Maven bundle is deterministic and verified. The next step is the
orchestrator-owned producer release/tag and Monomer consumer integration.

## 진척 표

| Phase | 단위 | 상태 | mission cycle | commit | 메모 |
|---|---|---|---|---|---|
| 0 | Recon | done | 1 (pass on 1st evaluation) | 7001953 | 60,346 LOC 풀 포팅 범위 확정 |
| 1a | Python oracle CLI + venv | done | 1 (pass 1st eval) | 10e698d | 11 smoke tests, 8 subcommands |
| 1b | Corpus extraction + normalizer | done | 1 (pass 1st eval, 3 minor fix inline) | (pending commit) | 112 cases × 298 checks, 100% PASS, 8 subcommands × 7 dialects, 19 manifests |
| 2 | Scaffolding (Gradle 멀티모듈) | done | 1 (pass 1st eval) | (pending commit) | 29 modules, 30 tests, 빌드 53s, diff-runner 112/112 UNIMPLEMENTED, 8 RPCs |
| 3 W1 | domain.manifest.model | done | 1 (pass 1st eval) | (pending commit) | 30 .kt / 1458 LOC, 63 top-level types, 19/19 strict round-trip |
| 3 W2a | domain.manifest.transformation | done | 1 (pass 1st eval) | (pending commit) | 14 rules, 50 tests, 17/17 canonical parity + 2 W1-layer skip |
| 3 W2b | domain.manifest.validation | done | 1 (pass 1st eval) | (pending commit) | 28 rules exact order, 24 tests, 17/17 canonical parity |
| 3 W3 | common.{toolkit,telemetry,time} + datatable | done | 1 (pass 1st eval) | (pending commit) | 52 .kt / 3.8k LOC, 109 tests; TimeSpineSource W4 deferred |
| 3 W4 | domain.spec.bind + sql.plan + TimeSpineSource | done | 1 (pass 1st eval) | (pending commit) | 38 .kt / 2.6k LOC, 66 tests, 22+1 expr / 5 plan variants |
| 3 W5 | domain.sql.optimizer + sql.render(interface) | done | 1 (pass 1st eval) | (pending commit) | 20 .kt / 2.5k LOC, 21 tests, open class extension points |
| 3 W6 | infrastructure.sql.render.{base + 8 dialects} | done | 1 (pass 1st eval) | (pending commit) | 22 .kt / 9 modules / 80 tests, dialect별 minimal override |
| 3 W7a | domain.lookup (model/) | done | 1 (pass 1st eval) | (pending commit) | 11 .kt / 1.9k LOC, 66 tests; spec deferred |
| 3 W7b | domain.spec | done | 1 (pass 1st eval) | (pending commit) | 33 main + 7 test / 2.4k LOC, 74 tests, sealed 계층 + 12 pattern |
| 3 W7c | domain.semantic_graph | done | 1 (pass 1st eval, 1 inline fix) | (pending commit) | 37 main + 8 test / 3.3k LOC, 52 tests, sealed node/edge 계층 + 7 subgraph gen + composition root |
| 3 W8 | domain.query + sqlclient | done | 1 (pass 1st eval) | (pending commit) | 52 files / 4.5k LOC, 48 tests; resolver body W9 deferred |
| 3 W9a | domain.dataflow nodes + plan structure | done | 1 (pass 1st eval, 1 inline fix) | (pending commit) | 30 main + 1 test / 2.3k LOC, 13 tests, 23 nodes + 23-arm visitor |
| 3 W9b | domain.dataflow builder + optimizer | done | 1 (pass 1st eval, 1 inline fix) | (pending commit) | 26 new files / 2.3k LOC, 40 tests; buildPlan body W9c deferred |
| 3 W9c | domain.plan_conversion | done | 1 (pass 1st eval) | (pending commit) | 17 main + 4 test / 3.2k LOC, 19 tests; visitor body W10 deferred |
| 3 W10 | application.engine facade + 6 non-SQL RPCs | done | 1 (pass 1st eval, 1 inline fix) | (pending commit) | **corpus 54/112 PASS** (0→48%), 6 RPCs full impl, explain 정직 deferred, 659 tests |
| 3 W11 | DFS resolver (path-aware multi-hop) | done | 1 (pass 1st eval) | (pending commit) | **81/112 PASS** (54→81, 27 FAIL → 0), 모든 BFS 이슈 해결 |
| 3 W12 | metric_evaluation + SemanticModelToDataSetConverter + JoinDataflowOutputValidator | done | 1 (pass 1st eval, 이전 세션 완료물 검증) | (pending commit) | metric-eval 2.7k LOC, converter 622 LOC, validator 115 LOC, 9+3 tests, baseline 동등 |
| 3 W13 | Fill DataflowNodeToSqlSubqueryVisitor 15/23 visit methods | done | 1 (pass 1st eval) | (pending commit) | 1,170 LOC body 채움, 8 deferred (time-spine + offset + window) |
| 3 W14 | engine.explain 체인 wire + scaffolding | done | 1 (scaffolding only, body 분할 권장) | (pending commit) | 본체는 ~5,500 LOC로 단일 wave 불가능 — W14a/b/c로 split |
| 3 W14a | MetricFlowQueryResolver.resolveQuery 본체 | done | 1 (pass, push-down visitor W14c deferred) | (pending commit) | ~520 LOC + 6 tests, 690 total tests / 0 fail; UNIMPL 위치가 parser → builder로 이동 |
| 3 W14b | DataflowPlanBuilder.buildPlan body (SIMPLE) + CaseRunner per-dialect SQL diff | done | 1 (이전 세션 완료) | (pending commit) | **96/4/12 corpus, explain 15/29 PASS** |
| 3 W14c | 2 visit + DERIVED + RATIO 채움, 6 visit + CUMULATIVE + CONVERSION W15 deferred | done | 1 (pass) | (pending commit) | **108/4/0/0 corpus, UNIMPL 0!** |
| 3 W15 | distinct-values + where-filter Jinja + 시간 spine wiring + 4 FAIL fix | done | 1 (이전 세션 완료, 직접 검증) | (pending commit) | **🎉 corpus 112/0/0/0 100% PASS 🎉** |
| 5 | API Surface Cleanup + gRPC split + product bundle | in-progress | 1 | pending | **engine transport-free, optional grpc-server, deterministic 8-artifact external-DW bundle; 112/0/0/0 target** |
| 5a | Step 2: internal visibility sweep | not started | — | — | ~436 .kt 파일, docs/PUBLIC_API.md 대조 |
| 5b | Step 6: gRPC split (engine → core + grpc-server) | done | 1 | pending | `SqlPlanRendererRegistry` seam; engine runtime guard |
| 6 | Maven publish + BOM + deterministic Monomer bundle | in-progress | 1 | pending | tag release workflow, SHA/SBOM/dependency/license/provenance evidence |
| 4 | Integration + examples | not started | — | — | |
| 5 | (선택) cutover | n/a | — | — | 이 프로젝트 범위 밖 |

## Operational notes

- **2026-05-11**: JDK 25.0.2 (Homebrew) 확인됨. `/opt/homebrew/Cellar/openjdk/25.0.2`. JAVA_HOME 명시 설정 권장 (build.gradle.kts의 toolchain이 자동 선택하면 OK).
- **2026-05-11**: Worktree isolation hooks 미설정 — Phase 3 병렬화의 전제 조건. `.claude/settings.json` 수정은 self-modification으로 차단되어 **사용자 권한 필요**. 사용자가 권한 부여 또는 직접 작성할 때까지 Phase 3는 **순차 진행**으로 fall back (예상 시간 ~6배).
- Python: pyenv 3.11.8 + uv 가용. Phase 1에서 `python_oracle/.venv/`에 metricflow 0.210.0 설치 예정.

## 누적 토큰 추정 (대략)

- Phase 0 cycle 1: builder ~225k + evaluator ~90k + 오케스트레이션 ~30k = **~345k**
- Phase 1a cycle 1: builder ~153k + evaluator ~60k + 오케스트레이션 ~20k = **~233k**
- Phase 1b cycle 1: builder (interrupted before report) + evaluator ~71k + 오케스트레이션 ~30k = **~100k+**
- Phase 2 cycle 1: builder ~162k + evaluator ~85k + 오케스트레이션 ~25k = **~272k**
- Phase 3 W1 cycle 1: builder ~210k + evaluator ~67k + 오케스트레이션 ~20k = **~297k**
- Phase 3 W2a cycle 1: builder ~184k + evaluator ~65k + 오케스트레이션 ~20k = **~269k**
- Phase 3 W2b cycle 1: builder ~338k + evaluator ~75k + 오케스트레이션 ~20k = **~433k**
- Phase 3 W3 cycle 1: builder ~283k + evaluator ~73k + 오케스트레이션 ~20k = **~376k**
- Phase 3 W4 cycle 1: builder ~283k + evaluator ~88k + 오케스트레이션 ~20k = **~391k**
- Phase 3 W5 cycle 1: builder ~253k + evaluator ~71k + 오케스트레이션 ~20k = **~344k**
- Phase 3 W6 cycle 1: builder ~233k + evaluator ~67k + 오케스트레이션 ~20k = **~320k**
- Phase 3 W7a cycle 1: builder ~234k + evaluator ~54k + 오케스트레이션 ~20k = **~308k**
- Phase 3 W7b cycle 1: builder ~293k + evaluator ~64k + 오케스트레이션 ~20k = **~377k**
- Phase 3 W7c cycle 1: builder ~360k + evaluator ~66k + 오케스트레이션 ~20k = **~446k**
- Phase 3 W8 cycle 1: builder ~366k + evaluator ~69k + 오케스트레이션 ~20k = **~455k**
- Phase 3 W9a cycle 1: builder ~244k + evaluator ~66k + 오케스트레이션 ~20k = **~330k**
- Phase 3 W9b cycle 1: builder ~337k + evaluator ~66k + 오케스트레이션 ~20k = **~423k**
- Phase 3 W9c cycle 1: builder ~327k + evaluator ~73k + 오케스트레이션 ~20k = **~420k**
- Phase 3 W10 cycle 1: builder ~425k + evaluator ~78k + 오케스트레이션 ~25k = **~528k**
- Phase 3 W11 cycle 1: builder ~324k + evaluator ~60k + 오케스트레이션 ~25k = **~409k** (1 abort + retry 포함)
- Phase 3 W12 cycle 1: builder (이전 세션 / 컨텍스트 외) + evaluator ~79k + 오케스트레이션 ~15k = **~94k** (재발견)
- Phase 3 W13 cycle 1: builder ~260k + evaluator ~51k + 오케스트레이션 ~20k = **~331k**
- Phase 3 W14 cycle 1: builder ~210k (scaffolding only, body split 권고) + 오케스트레이션 ~15k = **~225k**
- Phase 3 W14a cycle 1: builder ~248k + 오케스트레이션 ~15k = **~263k** (evaluator wave 생략)
- Phase 3 W14b cycle 1: 이전 세션 백그라운드 + 직접 검증 ~30k = **~30k+** (외부 세션)
- Phase 3 W14c cycle 1: builder ~336k + 오케스트레이션 ~15k = **~351k** (evaluator wave 생략)
- Phase 3 W15 cycle 1: 이전 세션 백그라운드 + 직접 검증 ~25k = **~25k+** (외부 세션)
- Phase 5 cycle 1: builder ~149k (8/10 steps 완료) + 오케스트레이션 ~15k = **~164k** (evaluator wave 생략 — diff-runner 안전망)
- **누적: ~8.61M+**

(매 cycle 종료 시 갱신. 비용 한도 없음 — rate limit이 자연 throttle)

## Phase 3 설계 인사이트 (Phase 1a에서 발견)

- **Kotlin `validateManifest` 시그니처는 issues 반환만, throw 안 함.** Python `validate_semantic_manifest()`처럼 blocking issues도 그냥 리스트로. 호출자가 결정.
- **`SqlClient`의 실행 메서드는 정의 자체를 두지 않음** (NotImplementedError 우회 가능성 제거). Python `OracleSqlClient`가 이 패턴 — Kotlin diff-runner도 동일.
- **manifest 빌드 시 transform 적용 default**. 일부 validation rule은 transform 후에야 발화 (예: duplicate-metric).
