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


if __name__ == "__main__":
    unittest.main()
