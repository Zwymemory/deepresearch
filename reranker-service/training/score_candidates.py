"""Score a fixed candidate file with either the base or a merged local model."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from .config import BASE_MODEL_ID, BASE_MODEL_REVISION, DEFAULT_MAX_LENGTH
from .hard_negatives import load_retrieval_run
from .manifest import sha256_file
from .scifact import build_split, load_scifact
from .text_format import format_pair


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--candidate-run", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model-path", type=Path)
    parser.add_argument("--model-id", help="Logical artifact id (defaults to manifest/path/base id)")
    parser.add_argument("--split", choices=("test", "strict-test", "dev"), default="test")
    parser.add_argument("--candidate-count", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--max-length", type=int, default=DEFAULT_MAX_LENGTH)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda", "mps"), default="auto")
    args = parser.parse_args()
    if args.candidate_count <= 0 or args.batch_size <= 0 or args.max_length <= 0:
        raise ValueError("candidate-count, batch-size, and max-length must be positive")

    import torch
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    dataset = load_scifact(args.data_dir)
    split = build_split(dataset)
    query_ids = {
        "test": split.test_query_ids,
        "strict-test": split.strict_test_query_ids,
        "dev": split.dev_query_ids,
    }[args.split]
    candidates = load_retrieval_run(args.candidate_run)
    missing = [qid for qid in query_ids if qid not in candidates]
    if missing:
        raise ValueError(f"candidate run is missing {len(missing)} queries; first={missing[:3]}")
    short = [qid for qid in query_ids if len(candidates[qid]) < args.candidate_count]
    if short:
        raise ValueError(
            f"fixed evaluation requires {args.candidate_count} candidates per query; "
            f"{len(short)} are short; first={short[:3]}"
        )
    unknown = {
        doc_id
        for qid in query_ids
        for doc_id in candidates[qid][: args.candidate_count]
        if doc_id not in dataset.corpus
    }
    if unknown:
        raise ValueError(f"candidate run references unknown documents; first={sorted(unknown)[:3]}")

    source = str(args.model_path.resolve()) if args.model_path else BASE_MODEL_ID
    manifest_path = args.model_path / "training-manifest.json" if args.model_path else None
    manifest_value = _load_manifest(manifest_path) if manifest_path and manifest_path.is_file() else {}
    manifest_model = manifest_value.get("model") if isinstance(manifest_value.get("model"), dict) else {}
    logical_model_id = (
        args.model_id
        or str(manifest_model.get("modelId") or "")
        or (args.model_path.name if args.model_path else BASE_MODEL_ID)
    )
    manifest_sha256 = sha256_file(manifest_path) if manifest_path and manifest_path.is_file() else None
    kwargs = {} if args.model_path else {"revision": BASE_MODEL_REVISION}
    tokenizer = AutoTokenizer.from_pretrained(source, **kwargs)
    model = AutoModelForSequenceClassification.from_pretrained(source, **kwargs)
    device = _resolve_device(torch, args.device)
    model = model.to(device)
    model.eval()

    def score(qid: str) -> tuple[list[tuple[str, float]], float]:
        query = dataset.queries[qid]
        doc_ids = candidates[qid][: args.candidate_count]
        documents = [dataset.corpus[doc_id] for doc_id in doc_ids]
        pairs = [format_pair(query, document.title, document.text) for document in documents]
        values: list[float] = []
        started = time.perf_counter()
        with torch.no_grad():
            for start in range(0, len(pairs), args.batch_size):
                encoded = tokenizer(
                    pairs[start:start + args.batch_size],
                    padding=True,
                    truncation=True,
                    max_length=args.max_length,
                    return_tensors="pt",
                )
                encoded = {name: tensor.to(device) for name, tensor in encoded.items()}
                logits = model(**encoded, return_dict=True).logits.view(-1)
                values.extend(logits.detach().float().cpu().tolist())
        if device.type == "cuda":
            torch.cuda.synchronize()
        elif device.type == "mps":
            torch.mps.synchronize()
        latency_ms = (time.perf_counter() - started) * 1000.0
        return sorted(zip(doc_ids, values), key=lambda item: (-item[1], item[0])), latency_ms

    # One unmeasured warm-up removes first-call initialization from p95.
    score(query_ids[0])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    candidate_digest = sha256_file(args.candidate_run)
    with args.output.open("w", encoding="utf-8") as target:
        for qid in query_ids:
            ranking, latency_ms = score(qid)
            target.write(json.dumps({
                "schemaVersion": 1,
                "queryId": qid,
                "modelId": logical_model_id,
                "baseRevision": BASE_MODEL_REVISION,
                "manifestSha256": manifest_sha256,
                "candidateRunSha256": candidate_digest,
                "rankedDocIds": [doc_id for doc_id, _ in ranking],
                "scores": [value for _, value in ranking],
                "latencyMs": latency_ms,
            }, ensure_ascii=False, sort_keys=True) + "\n")
    print(json.dumps({
        "modelId": logical_model_id,
        "modelSource": source,
        "queries": len(query_ids),
        "candidateRunSha256": candidate_digest,
        "output": str(args.output),
    }, ensure_ascii=False, indent=2, sort_keys=True))


def _load_manifest(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"training manifest must be an object: {path}")
    return value


def _resolve_device(torch, requested: str):
    if requested != "auto":
        return torch.device(requested)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


if __name__ == "__main__":
    main()
