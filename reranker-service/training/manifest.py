"""Machine-readable experiment manifest and artifact hashing."""

from __future__ import annotations

import hashlib
import importlib.metadata
import json
import os
import platform
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Mapping, Sequence

from .config import BASE_MODEL_ID, BASE_MODEL_REVISION, DEFAULT_SEED
from .scifact import SciFactDataset, SciFactSplit, source_hashes


LICENSES = {
    "baseModel": {
        "name": BASE_MODEL_ID,
        "license": "MIT",
        "source": "https://huggingface.co/BAAI/bge-reranker-base",
    },
    "scifactClaims": {
        "license": "CC BY 4.0",
        "source": "https://github.com/allenai/scifact",
    },
    "scifactCorpusAbstracts": {
        "license": "ODC-By 1.0 (upstream Semantic Scholar corpus terms apply)",
        "source": "https://github.com/beir-cellar/beir",
    },
}


def create_training_manifest(
    *,
    dataset: SciFactDataset,
    split: SciFactSplit,
    train_groups_path: Path,
    dev_groups_path: Path,
    output_model_dir: Path,
    model_id: str,
    hyperparameters: Mapping[str, object],
    training_result: Mapping[str, object],
    cloud_cost_cny: float,
    hardware: str,
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "experiment": "scifact-cross-encoder-lora-listwise-1-plus-5",
        "claimsBoundary": (
            "This is supervised Cross-Encoder ranking fine-tuning. "
            "It is not LLM SFT, DPO, RLHF, PPO, GRPO, or Agentic RL."
        ),
        "model": {
            "modelId": model_id,
            "baseModelId": BASE_MODEL_ID,
            "baseRevision": BASE_MODEL_REVISION,
            "method": "LoRA sequence-classification reranker; merged for serving",
            "artifactFilesSha256": directory_hashes(output_model_dir),
        },
        "data": {
            "dataset": "BEIR SciFact",
            "sourceFilesSha256": source_hashes(dataset.data_dir),
            "trainGroupsSha256": sha256_file(train_groups_path),
            "devGroupsSha256": sha256_file(dev_groups_path),
            "counts": {
                "trainQueries": len(split.train_query_ids),
                "devQueries": len(split.dev_query_ids),
                "officialTestQueries": len(split.test_query_ids),
                "strictTestQueries": len(split.strict_test_query_ids),
            },
            "removedTrainQueryIds": list(split.removed_train_query_ids),
            "split": split.to_dict(dataset),
        },
        "training": {
            "seed": DEFAULT_SEED,
            "hyperparameters": dict(hyperparameters),
            "result": dict(training_result),
        },
        "environment": {
            "python": sys.version.split()[0],
            "platform": platform.platform(),
            "hardware": hardware,
            "cloudCostCny": cloud_cost_cny,
            "packages": package_versions(("torch", "transformers", "peft", "accelerate")),
            "gitCommit": os.getenv("GIT_COMMIT", "unknown"),
        },
        "licenses": LICENSES,
        "distribution": {
            "weightsCommittedToGit": False,
            "policy": (
                "Keep trained weights outside Git. Re-check all upstream model/data "
                "terms before publishing an adapter or merged checkpoint."
            ),
        },
        "deploymentGate": (
            "Do not make this model the default until evaluate.py reports eligible=true "
            "on identical RRF Top20 candidates."
        ),
    }


def write_manifest(value: Mapping[str, object], path: str | Path) -> str:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    target.write_text(payload, encoding="utf-8")
    digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    target.with_suffix(target.suffix + ".sha256").write_text(
        f"{digest}  {target.name}\n", encoding="utf-8"
    )
    return digest


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def directory_hashes(path: str | Path) -> dict[str, str]:
    root = Path(path)
    return {
        str(file.relative_to(root)): sha256_file(file)
        for file in sorted(root.rglob("*"))
        if file.is_file() and file.name not in {"training-manifest.json", "training-manifest.json.sha256"}
    }


def package_versions(names: Sequence[str]) -> dict[str, str]:
    versions = {}
    for name in names:
        try:
            versions[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            versions[name] = "not-installed"
    return versions
