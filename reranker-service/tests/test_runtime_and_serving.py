from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import create_app
from runtime_config import RerankerRuntimeConfig
from training.config import BASE_MODEL_ID, BASE_MODEL_REVISION


class FakeEngine:
    device = "test"

    def __init__(self) -> None:
        self.pairs: list[tuple[str, str]] = []

    def score(self, pairs):
        self.pairs = list(pairs)
        return [0.1, 0.9]


def test_legacy_runtime_config_remains_compatible() -> None:
    config = RerankerRuntimeConfig.from_env({})
    assert config.model_source == BASE_MODEL_ID
    assert config.model_id == BASE_MODEL_ID
    assert config.base_revision == BASE_MODEL_REVISION
    assert config.remote_revision == BASE_MODEL_REVISION
    assert config.max_length == 512
    assert config.batch_size == 8


def test_local_manifest_supplies_auditable_identity(tmp_path: Path) -> None:
    model_dir = tmp_path / "merged"
    model_dir.mkdir()
    manifest = model_dir / "training-manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "model": {
                    "modelId": "local-v1",
                    "baseModelId": BASE_MODEL_ID,
                    "baseRevision": BASE_MODEL_REVISION,
                }
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    config = RerankerRuntimeConfig.from_env({"RERANK_MODEL_PATH": str(model_dir)})
    assert config.model_source == str(model_dir.resolve())
    assert config.model_id == "local-v1"
    assert config.remote_revision is None
    assert config.manifest_sha256 == hashlib.sha256(manifest.read_bytes()).hexdigest()


def test_explicit_missing_manifest_fails_fast(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="does not exist"):
        RerankerRuntimeConfig.from_env(
            {"RERANK_MANIFEST_PATH": str(tmp_path / "missing.json")}
        )


def test_old_rerank_contract_and_new_health_metadata() -> None:
    config = RerankerRuntimeConfig.from_env(
        {
            "RERANK_MODEL_ID": "logical-v1",
            "RERANK_MAX_LENGTH": "256",
            "RERANK_BATCH_SIZE": "2",
        }
    )
    engine = FakeEngine()
    with TestClient(create_app(config, engine)) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["model"] == "logical-v1"
        assert health.json()["baseRevision"] == BASE_MODEL_REVISION

        response = client.post(
            "/rerank",
            json={
                "query": "  query  with space ",
                "documents": [
                    {"id": "b", "title": "B", "content": "body"},
                    {"id": "a", "title": "A", "content": "body"},
                ],
            },
        )
        assert response.status_code == 200
        assert response.json() == {
            "model": "logical-v1",
            "results": [{"id": "a", "score": 0.9}, {"id": "b", "score": 0.1}],
        }
        assert engine.pairs[0] == ("query with space", "标题：B\n正文：body")


def test_empty_request_does_not_call_model() -> None:
    engine = FakeEngine()
    with TestClient(create_app(RerankerRuntimeConfig.from_env({}), engine)) as client:
        response = client.post("/rerank", json={"query": "q", "documents": []})
    assert response.status_code == 200
    assert response.json()["results"] == []
    assert engine.pairs == []
