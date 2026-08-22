import json
import pathlib
import unittest

from harness.manifest_loader import _normalize_repository_paths


class NormalizeRepositoryPathsTest(unittest.TestCase):
    def test_normalizes_nested_checkout_paths(self) -> None:
        repository_root = pathlib.Path("/workspace/metricflow-kotlin")
        value = {
            "repo_file_path": "/workspace/metricflow-kotlin/python_oracle/upstream/model.yaml",
            "nested": ["/workspace/metricflow-kotlin/corpus/request.json"],
        }

        self.assertEqual(
            {
                "repo_file_path": "python_oracle/upstream/model.yaml",
                "nested": ["corpus/request.json"],
            },
            _normalize_repository_paths(value, repository_root),
        )

    def test_preserves_relative_and_external_paths(self) -> None:
        repository_root = pathlib.Path("/workspace/metricflow-kotlin")

        self.assertEqual(
            ["already/relative.yaml", "/another/project/model.yaml", None],
            _normalize_repository_paths(
                ["already/relative.yaml", "/another/project/model.yaml", None],
                repository_root,
            ),
        )

    def test_normalizes_upstream_path_from_another_worktree(self) -> None:
        repository_root = pathlib.Path("/workspace/metricflow-kotlin")

        self.assertEqual(
            "python_oracle/upstream/model.yaml",
            _normalize_repository_paths(
                "/Users/developer/metricflow-kotlin/.worktrees/feature/python_oracle/upstream/model.yaml",
                repository_root,
            ),
        )

    def test_checked_in_requests_have_no_absolute_upstream_paths(self) -> None:
        corpus_root = pathlib.Path(__file__).resolve().parents[1] / "corpus"
        offenders = []
        for request_path in sorted(corpus_root.glob("*/request.json")):
            payload = json.loads(request_path.read_text())
            offenders.extend(
                (request_path.relative_to(corpus_root).as_posix(), value)
                for value in _iter_strings(payload)
                if pathlib.Path(value).is_absolute() and "python_oracle/upstream" in value
            )

        self.assertEqual([], offenders)


def _iter_strings(value):
    if isinstance(value, dict):
        for item in value.values():
            yield from _iter_strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from _iter_strings(item)
    elif isinstance(value, str):
        yield value


if __name__ == "__main__":
    unittest.main()
