# Contributing

기여해 주셔서 감사합니다. 큰 변경은 구현 전에 GitHub issue에서 문제와 공개 API
영향을 먼저 합의해 주세요.

## 개발 환경

- JDK 25
- 저장소에 포함된 Gradle Wrapper
- Python oracle을 다시 생성할 때만 Python 3.11과 `uv`

별도 Gradle 설치는 필요하지 않습니다.

## 검증

```bash
./gradlew verifyPublicRepository
python3 -m unittest harness.test_manifest_loader
```

첫 명령은 공개 모듈 테스트, Maven staging artifact, SBOM, 112개 차등 corpus와
Monomer 외부-DW product bundle의 Maven layout/SHA evidence를 검증합니다. 모든
변경은 checkout 경로나 시간에 의존하지 않고 재현 가능해야 합니다.

제품 번들만 확인할 때는 다음을 실행합니다.

```bash
./gradlew verifyMonomerProductBundle
```

태그 `v<version>`은 `build.gradle.kts`의 Maven version과 일치해야 합니다.
`.github/workflows/release.yml`은 동일한 검증을 다시 수행한 뒤 bundle과 checksum,
GitHub build-provenance attestation을 release에 업로드합니다.

## 변경 원칙

- SQL 실행 기능은 추가하지 않습니다. 이 라이브러리의 경계는 query planning과 SQL
  rendering까지입니다.
- `python_oracle/upstream/`은 [`UPSTREAM.md`](UPSTREAM.md)의 갱신 절차 외에는
  수정하지 않습니다.
- 공개 API와 Maven coordinate를 바꾸면 README와 관련 문서를 함께 갱신합니다.
- 커밋 메시지는 Conventional Commits 형식을 사용합니다.

기여는 이 저장소의 [`LICENSE`](LICENSE)에 따라 제공됩니다.
