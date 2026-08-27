"""Pure-stdlib runtime configuration for base and local reranker artifacts."""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from training.config import BASE_MODEL_ID, BASE_MODEL_REVISION


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


@dataclass(frozen=True)
class RerankerRuntimeConfig:
    model_source: str
    model_id: str
    base_model_id: str
    base_revision: str
    model_path: str | None
    manifest_path: str | None
    manifest_sha256: str | None
    max_length: int
    batch_size: int
    device: str

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "RerankerRuntimeConfig":
        values = os.environ if env is None else env
        legacy_model = values.get("RERANK_MODEL", BASE_MODEL_ID).strip() or BASE_MODEL_ID
        raw_path = values.get("RERANK_MODEL_PATH", "").strip()
        model_path = str(Path(raw_path).expanduser().resolve()) if raw_path else None
        model_source = model_path or legacy_model

        raw_manifest = values.get("RERANK_MANIFEST_PATH", "").strip()
        explicit_manifest = bool(raw_manifest)
        if explicit_manifest:
            manifest = Path(raw_manifest).expanduser().resolve()
        elif model_path:
            manifest = Path(model_path) / "training-manifest.json"
        else:
            manifest = None
        if explicit_manifest and manifest is not None and not manifest.is_file():
            raise ValueError(f"RERANK_MANIFEST_PATH does not exist: {manifest}")
        manifest_value = _read_manifest(manifest) if manifest and manifest.is_file() else {}
        manifest_model = _mapping(manifest_value.get("model"))

        model_id = (
            values.get("RERANK_MODEL_ID", "").strip()
            or str(manifest_model.get("modelId") or "").strip()
            or (Path(model_path).name if model_path else legacy_model)
        )
        base_model_id = (
            values.get("RERANK_BASE_MODEL_ID", "").strip()
            or str(manifest_model.get("baseModelId") or "").strip()
            or legacy_model
        )
        configured_revision = values.get("RERANK_BASE_REVISION", "").strip()
        manifest_revision = str(manifest_model.get("baseRevision") or "").strip()
        base_revision = configured_revision or manifest_revision or (
            BASE_MODEL_REVISION if base_model_id == BASE_MODEL_ID else "unresolved"
        )

        manifest_path = str(manifest) if manifest and manifest.is_file() else None
        manifest_sha256 = sha256_file(manifest) if manifest_path and manifest else None
        max_length = _positive_int(values.get("RERANK_MAX_LENGTH", "512"), "RERANK_MAX_LENGTH")
        batch_size = _positive_int(values.get("RERANK_BATCH_SIZE", "8"), "RERANK_BATCH_SIZE")
        device = values.get("RERANK_DEVICE", "auto").strip().lower() or "auto"
        if device not in {"auto", "cpu", "cuda", "mps"}:
            raise ValueError("RERANK_DEVICE must be one of auto, cpu, cuda, or mps")

        return cls(
            model_source=model_source,
            model_id=model_id,
            base_model_id=base_model_id,
            base_revision=base_revision,
            model_path=model_path,
            manifest_path=manifest_path,
            manifest_sha256=manifest_sha256,
            max_length=max_length,
            batch_size=batch_size,
            device=device,
        )

    @property
    def remote_revision(self) -> str | None:
        if self.model_path or self.base_revision == "unresolved":
            return None
        return self.base_revision


def _read_manifest(path: Path) -> Mapping[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid reranker training manifest: {path}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"reranker training manifest must be an object: {path}")
    return value


def _mapping(value: object) -> Mapping[str, object]:
    return value if isinstance(value, Mapping) else {}


def _positive_int(raw: str, name: str) -> int:
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value
