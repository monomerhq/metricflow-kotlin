#!/usr/bin/env python3

"""Materialize and validate the unsigned SLSA statement for a product bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


STATEMENT_TYPE = "https://in-toto.io/Statement/v1"
SLSA_PROVENANCE_TYPE = "https://slsa.dev/provenance/v1"
SOURCE_URI = "https://github.com/monomerhq/metricflow-kotlin"
PINNED_ACTION_PATTERN = re.compile(
    r"^([^@\s]+)@([0-9a-fA-F]{40})$"
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_object(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"unable to read {label}: {path}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object: {path}")
    return value


def pinned_actions(workflow_path: Path) -> list[dict[str, str]]:
    actions: list[dict[str, str]] = []
    for line in workflow_path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^\s*uses:\s*([^\s#]+)", line)
        if match is None:
            continue
        reference = match.group(1)
        pinned = PINNED_ACTION_PATTERN.fullmatch(reference)
        if pinned is None:
            raise ValueError(f"release workflow action is not pinned to a commit: {reference}")
        actions.append({"name": pinned.group(1), "ref": pinned.group(2).lower()})
    if not actions:
        raise ValueError("release workflow has no pinned actions")
    return actions


def source_from_manifest(manifest: dict[str, Any]) -> tuple[str, str]:
    source = manifest.get("source")
    if not isinstance(source, dict):
        raise ValueError("product manifest has no source object")
    uri = source.get("uri")
    commit = source.get("commit")
    if uri != SOURCE_URI:
        raise ValueError(f"product manifest source URI drift: {uri!r}")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("product manifest source commit is not a full Git SHA")
    return uri, commit


def manifest_artifacts(manifest: dict[str, Any]) -> list[dict[str, str]]:
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise ValueError("product manifest has no artifacts")
    result: list[dict[str, str]] = []
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise ValueError("product manifest artifact is not an object")
        coordinate = artifact.get("coordinate")
        relative_path = artifact.get("relativePath")
        digest = artifact.get("sha256")
        if not isinstance(coordinate, str) or not isinstance(relative_path, str):
            raise ValueError("product manifest artifact identity is malformed")
        if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise ValueError(f"product manifest artifact digest is malformed: {coordinate}")
        result.append(
            {
                "coordinate": coordinate,
                "relativePath": relative_path,
                "sha256": digest,
            }
        )
    return result


def build_statement(
    archive_path: Path,
    manifest_path: Path,
    workflow_path: Path,
    repository: str,
    source_commit: str,
    release_ref: str,
    release_tag: str,
    invocation_id: str,
    builder_id: str,
) -> dict[str, Any]:
    manifest = load_object(manifest_path, "product manifest")
    manifest_uri, manifest_commit = source_from_manifest(manifest)
    if repository != SOURCE_URI:
        raise ValueError(f"repository must be {SOURCE_URI}")
    if source_commit != manifest_commit:
        raise ValueError(
            "tagged source commit does not match the product manifest: "
            f"{source_commit} != {manifest_commit}"
        )
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        raise ValueError("source commit is not a full Git SHA")
    if not release_ref.startswith("refs/tags/") or release_ref != f"refs/tags/{release_tag}":
        raise ValueError("release provenance must be generated from the exact release tag")
    if not archive_path.is_file():
        raise ValueError(f"product archive is missing: {archive_path}")
    if not workflow_path.is_file():
        raise ValueError(f"release workflow is missing: {workflow_path}")

    archive_digest = sha256_file(archive_path)
    workflow_digest = sha256_file(workflow_path)
    actions = pinned_actions(workflow_path)
    artifacts = manifest_artifacts(manifest)
    artifact_set_digest = manifest.get("artifactSetDigest")
    if not isinstance(artifact_set_digest, str) or not re.fullmatch(
        r"sha256:[0-9a-f]{64}", artifact_set_digest
    ):
        raise ValueError("product manifest artifact-set digest is malformed")
    workflow_uri = f"{repository}/blob/{source_commit}/.github/workflows/release.yml"
    invocation_uri = (
        invocation_id
        if invocation_id.startswith(("https://", "http://"))
        else f"https://github.com/{repository.removeprefix('https://github.com/')}/actions/runs/{invocation_id}"
    )

    statement = {
        "_type": STATEMENT_TYPE,
        "subject": [
            {
                "name": archive_path.name,
                "digest": {"sha256": archive_digest},
            }
        ],
        "predicateType": SLSA_PROVENANCE_TYPE,
        "predicate": {
            "buildDefinition": {
                "buildType": "https://slsa.dev/provenance/v1",
                "externalParameters": {
                    "source": {
                        "uri": manifest_uri,
                        "digest": {"gitCommit": source_commit},
                    },
                    "workflow": {
                        "uri": workflow_uri,
                        "ref": release_ref,
                        "path": ".github/workflows/release.yml",
                        "digest": {"sha256": workflow_digest},
                    },
                    "release": {"tag": release_tag},
                    "productBundle": {
                        "name": archive_path.name,
                        "sha256": archive_digest,
                        "artifactSetDigest": artifact_set_digest,
                    },
                    "productArtifacts": artifacts,
                },
                "internalParameters": {
                    "runner": "ubuntu-latest",
                    "actions": actions,
                },
                "resolvedDependencies": [
                    {
                        "uri": manifest_uri,
                        "digest": {"gitCommit": source_commit},
                    },
                    {
                        "uri": workflow_uri,
                        "digest": {"sha256": workflow_digest},
                    },
                ],
            },
            "runDetails": {
                "builder": {"id": builder_id},
                "metadata": {"invocationId": invocation_uri},
            },
        },
    }
    validate_statement(
        statement,
        archive_path=archive_path,
        manifest=manifest,
        workflow_path=workflow_path,
        source_commit=source_commit,
        release_ref=release_ref,
        release_tag=release_tag,
    )
    return statement


def validate_statement(
    statement: dict[str, Any],
    *,
    archive_path: Path,
    manifest: dict[str, Any],
    workflow_path: Path,
    source_commit: str,
    release_ref: str,
    release_tag: str,
) -> None:
    if statement.get("_type") != STATEMENT_TYPE:
        raise ValueError("provenance is not an in-toto Statement v1")
    if statement.get("predicateType") != SLSA_PROVENANCE_TYPE:
        raise ValueError("provenance is not SLSA provenance v1")
    subjects = statement.get("subject")
    if not isinstance(subjects, list) or len(subjects) != 1:
        raise ValueError("provenance must bind exactly one product ZIP subject")
    subject = subjects[0]
    if not isinstance(subject, dict) or subject.get("name") != archive_path.name:
        raise ValueError("provenance subject does not identify the product ZIP")
    digest = subject.get("digest", {}).get("sha256") if isinstance(subject, dict) else None
    expected_digest = sha256_file(archive_path)
    if digest != expected_digest:
        raise ValueError("provenance subject does not bind the exact product ZIP digest")

    predicate = statement.get("predicate")
    build_definition = predicate.get("buildDefinition") if isinstance(predicate, dict) else None
    if not isinstance(build_definition, dict):
        raise ValueError("provenance has no SLSA build definition")
    dependencies = build_definition.get("resolvedDependencies")
    manifest_uri, manifest_commit = source_from_manifest(manifest)
    matching_sources = [
        dependency
        for dependency in dependencies or []
        if isinstance(dependency, dict)
        and dependency.get("uri") == manifest_uri
        and dependency.get("digest", {}).get("gitCommit") == source_commit
    ]
    if len(matching_sources) != 1 or manifest_commit != source_commit:
        raise ValueError("provenance does not bind the exact source URI and commit")
    external_parameters = build_definition.get("externalParameters")
    if not isinstance(external_parameters, dict):
        raise ValueError("provenance has no external build parameters")
    workflow = external_parameters.get("workflow")
    if not isinstance(workflow, dict) or workflow.get("ref") != release_ref:
        raise ValueError("provenance does not bind the exact release workflow ref")
    release = external_parameters.get("release")
    if not isinstance(release, dict) or release.get("tag") != release_tag:
        raise ValueError("provenance does not bind the exact release tag")
    product_bundle = external_parameters.get("productBundle")
    if (
        not isinstance(product_bundle, dict)
        or product_bundle.get("name") != archive_path.name
        or product_bundle.get("sha256") != expected_digest
    ):
        raise ValueError("provenance product bundle metadata is not exact")
    run_details = predicate.get("runDetails")
    builder = run_details.get("builder") if isinstance(run_details, dict) else None
    metadata = run_details.get("metadata") if isinstance(run_details, dict) else None
    if not isinstance(builder, dict) or not isinstance(builder.get("id"), str):
        raise ValueError("provenance has no builder identity")
    if not isinstance(metadata, dict) or not isinstance(metadata.get("invocationId"), str):
        raise ValueError("provenance has no workflow invocation identity")
    if build_definition.get("internalParameters", {}).get("actions") != pinned_actions(workflow_path):
        raise ValueError("provenance action pin set does not match the release workflow")
    if release_ref == "refs/heads/main" or release_ref == "main":
        raise ValueError("provenance must not bind mutable main")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--workflow", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--validate", type=Path)
    parser.add_argument("--repository", default=SOURCE_URI)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--release-ref", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--invocation-id", required=True)
    parser.add_argument("--builder-id", default="https://github.com/actions/runner")
    args = parser.parse_args()
    if (args.output is None) == (args.validate is None):
        parser.error("exactly one of --output or --validate is required")
    return args


def main() -> int:
    args = parse_args()
    manifest = load_object(args.manifest, "product manifest")
    if args.validate is not None:
        statement = load_object(args.validate, "provenance statement")
        validate_statement(
            statement,
            archive_path=args.archive,
            manifest=manifest,
            workflow_path=args.workflow,
            source_commit=args.source_commit,
            release_ref=args.release_ref,
            release_tag=args.release_tag,
        )
        return 0

    statement = build_statement(
        archive_path=args.archive,
        manifest_path=args.manifest,
        workflow_path=args.workflow,
        repository=args.repository,
        source_commit=args.source_commit,
        release_ref=args.release_ref,
        release_tag=args.release_tag,
        invocation_id=args.invocation_id,
        builder_id=args.builder_id,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(statement, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
