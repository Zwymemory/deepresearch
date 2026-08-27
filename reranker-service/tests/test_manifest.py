from __future__ import annotations

import hashlib
from pathlib import Path

from training.config import BASE_MODEL_ID, BASE_MODEL_REVISION
from training.manifest import create_training_manifest, write_manifest
from training.scifact import SciFactDataset, SciFactDocument, SciFactSplit


def test_manifest_records_revision_hashes_licenses_and_distribution_boundary(tmp_path: Path) -> None:
    data = tmp_path / "data"
    (data / "qrels").mkdir(parents=True)
    for relative, content in {
        "corpus.jsonl": "{}\n",
        "queries.jsonl": "{}\n",
        "qrels/train.tsv": "query-id\tcorpus-id\tscore\n",
        "qrels/test.tsv": "query-id\tcorpus-id\tscore\n",
    }.items():
        (data / relative).write_text(content, encoding="utf-8")
    dataset = SciFactDataset(
        {"d": SciFactDocument("d", "title", "body")},
        {"q": "query", "t": "test"},
        {"q": frozenset({"d"})},
        {"t": frozenset({"test-doc"})},
        data,
    )
    split = SciFactSplit(("q",), (), ("t",), ("t",), (), (), 42)
    train_groups = tmp_path / "train.jsonl"
    dev_groups = tmp_path / "dev.jsonl"
    train_groups.write_text("train\n", encoding="utf-8")
    dev_groups.write_text("dev\n", encoding="utf-8")
    model_dir = tmp_path / "model"
    model_dir.mkdir()
    (model_dir / "model.safetensors").write_bytes(b"not-real-weights")

    manifest = create_training_manifest(
        dataset=dataset,
        split=split,
        train_groups_path=train_groups,
        dev_groups_path=dev_groups,
        output_model_dir=model_dir,
        model_id="unit-test-model",
        hyperparameters={"maxEpochs": 4},
        training_result={"bestEpoch": 1},
        cloud_cost_cny=0,
        hardware="unit-test",
    )
    assert manifest["model"]["baseModelId"] == BASE_MODEL_ID  # type: ignore[index]
    assert manifest["model"]["baseRevision"] == BASE_MODEL_REVISION  # type: ignore[index]
    assert manifest["licenses"]["baseModel"]["license"] == "MIT"  # type: ignore[index]
    assert manifest["distribution"]["weightsCommittedToGit"] is False  # type: ignore[index]
    assert "model.safetensors" in manifest["model"]["artifactFilesSha256"]  # type: ignore[index]

    path = model_dir / "training-manifest.json"
    digest = write_manifest(manifest, path)
    assert digest == hashlib.sha256(path.read_bytes()).hexdigest()
    assert path.with_suffix(".json.sha256").is_file()
