# Upstream provenance

이 저장소의 Kotlin 구현은 dbt Labs의
[MetricFlow](https://github.com/dbt-labs/metricflow)를 기준으로 개발하고 차등
검증한다.

## 고정된 기준

- Release: [`v0.210.0`](https://github.com/dbt-labs/metricflow/releases/tag/v0.210.0)
- Git commit: `b1708dbafcd1f01bf9f1cee6368ad17403c3d99a`
- License: Apache License 2.0
- Vendored location: `python_oracle/upstream/`

vendored snapshot은 공식 태그의 4,669개 tracked 파일 중 엔진, semantic
interfaces, 테스트 fixture 및 SQL snapshot에 필요한 4,563개 파일을 보존한다.
dbt CLI 패키지(`dbt-metricflow/`), 로컬 data warehouse 구성, 이미지 asset은
Kotlin 포트의 oracle 경계가 아니므로 포함하지 않는다.

루트 [`LICENSE`](LICENSE)는 upstream Apache-2.0 본문을 보존한다. 파생 저작물과
의존성 attribution은 [`NOTICE`](NOTICE) 및
`python_oracle/upstream/ATTRIBUTION.md`에 기록한다.

## 갱신 규칙

upstream 버전 변경은 `python_oracle/upstream/` 전체를 새 태그의 동일한 범위로
교체하는 하나의 changeset으로 수행한다. 부분 파일만 임의 수정하지 않는다.
같은 changeset에서 다음을 함께 갱신하고 검증한다.

1. 이 문서의 태그와 커밋
2. `python_oracle/pyproject.toml`의 `metricflow` 버전 핀
3. corpus와 attribution
4. `./gradlew verifyPublicRepository` 결과
