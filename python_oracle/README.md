# python_oracle

차등 테스트(differential testing)와 학습을 위한 Python 측 진실의 출처(source of truth).

## 무엇이 들어 있나

`upstream/` — **metricflow 0.210.0의 엔진·의미 모델·테스트 snapshot**. 공식
`v0.210.0` 태그의 커밋 `b1708dbafcd1f01bf9f1cee6368ad17403c3d99a`에서
dbt CLI, 로컬 DW 설정, 마케팅 에셋을 제외해 vendoring했다. 정확한 경계와 검증
방법은 [`../UPSTREAM.md`](../UPSTREAM.md)에 기록한다.

```
upstream/
├── README.md, GLOSSARY.md, TENETS.md, AGENTS.md, CHANGELOG.md, CONTRIBUTING.md, LICENSE
├── metricflow/                       148 .py files, 26.0k LOC   ← 엔진 코어
├── metricflow_semantics/             306 .py files, 28.7k LOC   ← 의미 lookup, spec, 변환
├── metricflow_semantic_interfaces/   120 .py files, 13.0k LOC   ← Pydantic 모델, 검증, 변환
├── tests_metricflow/                 213 .py + 2,854 .sql + 61 .yaml   ← 핵심 corpus
├── tests_metricflow_semantics/
├── tests_metricflow_semantic_interfaces/
├── pyproject.toml, mypy.ini, ruff.toml, pytest.ini
└── ...
```

총 ~67.7k LOC of Python (테스트 제외). 테스트 디렉토리에는 SQL snapshot 2,854개, YAML fixture 61개 — 우리 차등 테스트 corpus의 1차 자료.

`engine_wrapper.py` — `monomer-metricflow-engine`의 wrapper 사본. `RenderSql`/`ListGroupBys` 진입점 진입경로 학습용. 0.210.0 호환으로 import 경로 갱신됨.

## 왜 이렇게 두는가

1. **학습 자료**: 사용자가 Kotlin 포팅 결과와 Python 원본을 직접 비교하며 의미층 엔진의 작동을 이해. README/GLOSSARY/TENETS는 도메인 어휘와 설계 의도의 출처.
2. **차등 테스트 oracle**: `tests_metricflow/snapshots/`의 expected SQL 2,854개를 그대로 corpus로 사용. metricflow 자체 검증 통과한 케이스 = 신뢰 가능한 ground truth.
3. **포팅 정확성 reference**: 어떤 모듈을 옮길 때 grep/AST/cross-reference가 한 자리에서 가능.

## 절대 수정하지 말 것

`upstream/`은 **read-only oracle**. 정확성/학습의 기준이므로 손대지 말 것. 버전 업그레이드는 디렉토리 통째 교체.

`engine_wrapper.py`는 Kotlin 구현과 호출 경계를 비교하기 위한 프로젝트 소유의
얇은 wrapper이며 수정 가능하다.

## 진입점 (Kotlin이 재현해야 하는 메서드)

풀 포팅 대상이지만 **SQL 실행 로직은 제외**. 엔진은 SQL 생성까지만 책임. 모두 `MetricFlowEngine` (`upstream/metricflow/engine/metricflow_engine.py`)에서 출발:

| 메서드 | 책임 |
|---|---|
| `explain(request)` | 쿼리 → 실행 계획 + 렌더링된 SQL. **실행 안 함** |
| `list_metrics(include_dimensions)` | 컴파일된 메트릭 카탈로그 |
| `list_dimensions(metric_names=None)` | 메트릭별/전체 dimension |
| `entities_for_metrics(metric_names)` | 메트릭별 entity |
| `list_group_bys(metric_names)` | dimension + entity 통합 |
| `list_saved_queries()` | 저장된 쿼리 |
| `explain_get_dimension_values(...)` | dimension 값 조회 SQL 생성만 |

추가로 `metricflow_semantic_interfaces.validations.semantic_manifest_validator.SemanticManifestValidator`가 의미모델 검증을 담당 — 풀 포팅에 포함.

**명시 제외**: `MetricFlowEngine.query()` (실행 포함), `metricflow.execution/` 전체, `SqlClient`의 실행 메서드(`query`/`execute`/`dry_run`), executor 구현체. `SqlClient`는 렌더에 필요한 메타(`sql_engine_type`, `render_bind_parameter_key`)만 포팅.

`engine_wrapper.py`는 그중 두 개(`generate_sql`/`list_group_bys`)만 노출하는 “얇은” 사본. monomer-metricflow-engine 운영 흐름을 이해할 때만 참고.

## 버전 핀

- metricflow: **0.210.0** (2026-04-28, Apache-2.0)
- 0.208.1 → 0.210.0 변경: `dbt_semantic_interfaces` → `metricflow_semantic_interfaces` 패키지명, 단일 PyPI 패키지로 통합, `MetricFlowEngine.get_measures_for_metrics` 제거.

## 호환성 smoke test (이미 통과)

```
$ source .venv/bin/activate
$ python -c "from engine_wrapper import MetricFlowSqlEngine; ..."
Wrapper imports OK
Engine created OK   (단, time_spine을 가진 manifest 필요)
```

## Oracle CLI (Phase 1a)

8개 진입점을 stdin/stdout JSON으로 노출하는 차등 테스트용 CLI.
``upstream/`` 은 학습/oracle source용 read-only 트리이고, ``.venv/`` 가
실제로 돌아가는 oracle (``metricflow==0.210.0`` 설치본) 이다.

### 설치 / 빌드

```bash
cd python_oracle
uv venv .venv --python 3.11
uv pip install --python .venv/bin/python -e .
.venv/bin/python -m pytest tests/
```

### 호출 방법

```bash
# 활성화 없이 직접 호출
python_oracle/.venv/bin/python python_oracle/cli.py <subcommand> < input.json

# venv 활성화 후
source python_oracle/.venv/bin/activate
python -m oracle <subcommand> < input.json
metricflow-oracle <subcommand>   < input.json
```

### 8개 subcommand

| Subcommand | Python source of truth |
|---|---|
| `explain` | `MetricFlowEngine.explain(MetricFlowQueryRequest)` |
| `list_metrics` | `MetricFlowEngine.list_metrics(include_dimensions=bool)` |
| `list_dimensions` | `MetricFlowEngine.list_dimensions(metric_names=None or list)` |
| `entities_for_metrics` | `MetricFlowEngine.entities_for_metrics(metric_names)` |
| `list_group_bys` | `MetricFlowEngine.list_group_bys(metric_names)` |
| `list_saved_queries` | `MetricFlowEngine.list_saved_queries()` |
| `explain_get_dimension_values` | `MetricFlowEngine.explain_get_dimension_values(...)` |
| `validate_manifest` | `SemanticManifestValidator.validate_semantic_manifest(manifest)` |

각 subcommand 의 입출력 JSON 스키마는 [cli/SCHEMA.md](cli/SCHEMA.md) 참고.

### 예시

```bash
# 유효한 minimal manifest 검증 (issues=[])
python_oracle/.venv/bin/python python_oracle/cli.py \
    validate_manifest < python_oracle/tests/fixtures/minimal_valid_manifest.json

# explain 호출 (manifest + args 한 덩어리)
python_oracle/.venv/bin/python -c '
import json, sys
with open("python_oracle/tests/fixtures/minimal_valid_manifest.json") as f:
    m = json.load(f)
m["args"] = {"metric_names": ["bookings"], "group_by_names": ["metric_time__day"]}
json.dump(m, sys.stdout)' | \
    python_oracle/.venv/bin/python python_oracle/cli.py explain
```

### 파일 위치

```
python_oracle/
├── pyproject.toml             # uv/pip 진입점, metricflow==0.210.0 핀
├── cli.py                     # 루트 진입점 (python python_oracle/cli.py <sub>)
├── oracle/                    # 패키지
│   ├── cli.py                 # 디스패처 (python -m oracle <sub>)
│   ├── manifest.py            # PydanticSemanticManifest 생성
│   ├── engine_factory.py      # MetricFlowEngine + OracleSqlClient (실행 없음)
│   ├── serialize.py           # 도메인 객체 -> JSON-friendly dict
│   └── commands/              # 8개 subcommand
├── cli/SCHEMA.md              # 입출력 JSON 스키마
├── tests/
│   ├── test_smoke.py          # subcommand 당 1+ smoke test (11개)
│   └── fixtures/              # minimal_valid_manifest.json, minimal_invalid_manifest.json
├── .venv/                     # uv venv (.gitignore)
├── engine_wrapper.py          # 참고용 monomer wrapper (CLI 와 무관)
└── upstream/                  # metricflow 0.210.0 read-only 트리
```
