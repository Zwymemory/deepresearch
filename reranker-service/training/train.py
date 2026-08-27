"""LoRA listwise fine-tuning for the pinned BGE Cross-Encoder.

Each group contains exactly one positive followed by five hard negatives. The
six logits are optimized with a listwise softmax cross-entropy objective whose
target is index zero. Validation and early stopping use grouped nDCG@10.
"""

from __future__ import annotations

import argparse
import json
import math
import platform
import random
import shutil
import time
from contextlib import nullcontext
from pathlib import Path
from typing import Sequence

from .config import (
    BASE_MODEL_ID,
    BASE_MODEL_REVISION,
    DEFAULT_MAX_EPOCHS,
    DEFAULT_MAX_LENGTH,
    DEFAULT_NEGATIVE_COUNT,
    DEFAULT_SEED,
    LORA_ALPHA,
    LORA_DROPOUT,
    LORA_MODULES_TO_SAVE,
    LORA_R,
    LORA_TARGET_MODULES,
)
from .hard_negatives import TrainingGroup, load_groups
from .manifest import create_training_manifest, write_manifest
from .scifact import SciFactDataset, build_split, load_scifact
from .text_format import format_pair


def validate_training_groups(groups: Sequence[TrainingGroup]) -> None:
    if not groups:
        raise ValueError("training groups cannot be empty")
    for group in groups:
        group.validate(DEFAULT_NEGATIVE_COUNT)


def validate_group_alignment(
    groups: Sequence[TrainingGroup],
    expected_query_ids: Sequence[str],
    dataset: SciFactDataset,
    *,
    split_name: str,
) -> None:
    """Fail before model loading if mined groups do not match the audited split."""

    validate_training_groups(groups)
    expected = set(expected_query_ids)
    observed = {group.query_id for group in groups}
    if observed != expected:
        missing = sorted(expected - observed)[:5]
        unexpected = sorted(observed - expected)[:5]
        raise ValueError(
            f"{split_name} groups do not match the deterministic split; "
            f"missing={missing}, unexpected={unexpected}"
        )
    positives_by_query: dict[str, list[str]] = {}
    for group in groups:
        qid = group.query_id
        positives_by_query.setdefault(qid, []).append(group.positive.doc_id)
        if group.query != dataset.queries[qid]:
            raise ValueError(f"{split_name} query text differs from SciFact source for {qid}")
        relevant = set(dataset.train_qrels[qid])
        if group.positive.doc_id not in relevant:
            raise ValueError(f"{split_name} group {qid} uses an unlabelled positive")
        if any(document.doc_id in relevant for document in group.negatives):
            raise ValueError(f"{split_name} group {qid} contains a false negative")
        for document in (group.positive,) + group.negatives:
            source = dataset.corpus.get(document.doc_id)
            if source is None:
                raise ValueError(f"{split_name} group {qid} contains unknown doc {document.doc_id}")
            if document.title != source.title or document.content != source.text:
                raise ValueError(
                    f"{split_name} group {qid} document {document.doc_id} differs from source"
                )
    for qid in expected:
        observed_positives = positives_by_query[qid]
        expected_positives = set(dataset.train_qrels[qid])
        if len(observed_positives) != len(set(observed_positives)):
            raise ValueError(f"{split_name} groups duplicate a positive for {qid}")
        if set(observed_positives) != expected_positives:
            raise ValueError(
                f"{split_name} groups do not cover every labelled positive for {qid}"
            )


def grouped_ndcg_at_10(logit_rows: Sequence[Sequence[float]]) -> float:
    """Mean nDCG@10 where the sole relevant item is always row index zero."""

    if not logit_rows:
        raise ValueError("logit_rows cannot be empty")
    scores = []
    for row in logit_rows:
        if len(row) != 1 + DEFAULT_NEGATIVE_COUNT:
            raise ValueError("each validation row must contain one positive and five negatives")
        ranked_indices = sorted(range(len(row)), key=lambda index: (-float(row[index]), index))
        positive_rank = ranked_indices.index(0) + 1
        scores.append(1.0 / math.log2(positive_rank + 1))
    return sum(scores) / len(scores)


def run_training(args: argparse.Namespace) -> dict[str, object]:
    if not 1 <= args.max_epochs <= DEFAULT_MAX_EPOCHS:
        raise ValueError(f"max_epochs must be in [1, {DEFAULT_MAX_EPOCHS}]")
    if args.cloud_cost_cny < 0 or args.cloud_cost_cny > 100:
        raise ValueError("cloud_cost_cny must be in [0, 100]")
    for name in ("batch_size", "eval_batch_size", "max_length", "early_stopping_patience"):
        if getattr(args, name) <= 0:
            raise ValueError(f"{name} must be positive")
    if args.learning_rate <= 0 or args.max_grad_norm <= 0:
        raise ValueError("learning_rate and max_grad_norm must be positive")
    if args.weight_decay < 0 or not 0.0 <= args.warmup_ratio < 1.0:
        raise ValueError("weight_decay must be non-negative and warmup_ratio must be in [0, 1)")

    # Validate the exact leakage-audited split before importing ML libraries or
    # touching model weights.  This makes an accidental stale/foreign group file
    # fail cheaply and prevents a formal run from bypassing the data contract.
    dataset = load_scifact(args.data_dir)
    split = build_split(dataset)
    train_groups = load_groups(args.train_groups)
    dev_groups = load_groups(args.dev_groups)
    validate_group_alignment(
        train_groups,
        split.train_query_ids,
        dataset,
        split_name="train",
    )
    validate_group_alignment(
        dev_groups,
        split.dev_query_ids,
        dataset,
        split_name="dev",
    )
    effective_max_epochs = args.max_epochs
    if args.smoke:
        train_groups = train_groups[: min(8, len(train_groups))]
        dev_groups = dev_groups[: min(4, len(dev_groups))]
        effective_max_epochs = 1

    import torch
    import torch.nn.functional as functional
    from peft import LoraConfig, PeftModel, TaskType, get_peft_model
    from torch.utils.data import DataLoader
    from transformers import AutoModelForSequenceClassification, AutoTokenizer
    from transformers.optimization import get_linear_schedule_with_warmup

    _set_seed(torch, DEFAULT_SEED)
    device = _resolve_device(torch, args.device)
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL_ID, revision=BASE_MODEL_REVISION)
    base_model = AutoModelForSequenceClassification.from_pretrained(
        BASE_MODEL_ID,
        revision=BASE_MODEL_REVISION,
        num_labels=1,
    )
    lora_config = LoraConfig(
        task_type=TaskType.SEQ_CLS,
        r=LORA_R,
        lora_alpha=LORA_ALPHA,
        lora_dropout=LORA_DROPOUT,
        target_modules=list(LORA_TARGET_MODULES),
        modules_to_save=list(LORA_MODULES_TO_SAVE),
        bias="none",
    )
    model = get_peft_model(base_model, lora_config).to(device)

    def collate(groups: Sequence[TrainingGroup]):
        pairs = []
        for group in groups:
            documents = (group.positive,) + group.negatives
            pairs.extend(
                format_pair(group.query, document.title, document.content)
                for document in documents
            )
        encoded = tokenizer(
            pairs,
            padding=True,
            truncation=True,
            max_length=args.max_length,
            return_tensors="pt",
        )
        return {name: tensor.to(device) for name, tensor in encoded.items()}, len(groups)

    generator = torch.Generator()
    generator.manual_seed(DEFAULT_SEED)
    train_loader = DataLoader(
        train_groups,
        batch_size=args.batch_size,
        shuffle=True,
        generator=generator,
        collate_fn=collate,
    )
    dev_loader = DataLoader(
        dev_groups,
        batch_size=args.eval_batch_size,
        shuffle=False,
        collate_fn=collate,
    )
    optimizer = torch.optim.AdamW(
        (parameter for parameter in model.parameters() if parameter.requires_grad),
        lr=args.learning_rate,
        weight_decay=args.weight_decay,
    )
    total_steps = effective_max_epochs * len(train_loader)
    warmup_steps = int(total_steps * args.warmup_ratio)
    scheduler = get_linear_schedule_with_warmup(optimizer, warmup_steps, total_steps)

    output_dir = Path(args.output_dir)
    adapter_dir = output_dir / "adapter-best"
    merged_dir = output_dir / "merged"
    output_dir.mkdir(parents=True, exist_ok=True)
    best_ndcg = -1.0
    best_epoch = 0
    stale_epochs = 0
    history: list[dict[str, float | int]] = []
    started = time.perf_counter()

    for epoch in range(1, effective_max_epochs + 1):
        model.train()
        losses: list[float] = []
        for encoded, group_count in train_loader:
            optimizer.zero_grad(set_to_none=True)
            autocast = (
                torch.autocast(device_type="cuda", dtype=torch.float16)
                if device.type == "cuda" and args.fp16
                else nullcontext()
            )
            with autocast:
                logits = model(**encoded, return_dict=True).logits.view(
                    group_count, 1 + DEFAULT_NEGATIVE_COUNT
                )
                labels = torch.zeros(group_count, dtype=torch.long, device=device)
                loss = functional.cross_entropy(logits, labels)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), args.max_grad_norm)
            optimizer.step()
            scheduler.step()
            losses.append(float(loss.detach().cpu()))

        dev_ndcg = _evaluate_grouped_ndcg(torch, model, dev_loader)
        epoch_result = {
            "epoch": epoch,
            "meanTrainLoss": sum(losses) / len(losses),
            "devGroupedNDCG@10": dev_ndcg,
        }
        history.append(epoch_result)
        print(json.dumps(epoch_result, sort_keys=True))
        if dev_ndcg > best_ndcg + 1e-12:
            best_ndcg = dev_ndcg
            best_epoch = epoch
            stale_epochs = 0
            if adapter_dir.exists():
                shutil.rmtree(adapter_dir)
            model.save_pretrained(adapter_dir, safe_serialization=True)
            tokenizer.save_pretrained(adapter_dir)
        else:
            stale_epochs += 1
            if stale_epochs >= args.early_stopping_patience:
                break

    duration_seconds = time.perf_counter() - started
    del model, base_model
    if device.type == "cuda":
        torch.cuda.empty_cache()
    merge_base = AutoModelForSequenceClassification.from_pretrained(
        BASE_MODEL_ID,
        revision=BASE_MODEL_REVISION,
        num_labels=1,
    )
    merged_model = PeftModel.from_pretrained(merge_base, adapter_dir).merge_and_unload()
    if merged_dir.exists():
        shutil.rmtree(merged_dir)
    merged_dir.mkdir(parents=True)
    merged_model.save_pretrained(merged_dir, safe_serialization=True)
    tokenizer.save_pretrained(merged_dir)

    hyperparameters = {
        "objective": "listwise softmax cross-entropy",
        "groupShape": "1 positive + 5 hard negatives",
        "lora": {
            "r": LORA_R,
            "alpha": LORA_ALPHA,
            "dropout": LORA_DROPOUT,
            "targetModules": list(LORA_TARGET_MODULES),
            "modulesToSave": list(LORA_MODULES_TO_SAVE),
        },
        "requestedMaxEpochs": args.max_epochs,
        "effectiveMaxEpochs": effective_max_epochs,
        "earlyStoppingMetric": "dev grouped nDCG@10",
        "earlyStoppingPatience": args.early_stopping_patience,
        "learningRate": args.learning_rate,
        "weightDecay": args.weight_decay,
        "warmupRatio": args.warmup_ratio,
        "batchSize": args.batch_size,
        "evalBatchSize": args.eval_batch_size,
        "maxLength": args.max_length,
        "fp16": bool(args.fp16 and device.type == "cuda"),
    }
    training_result = {
        "bestEpoch": best_epoch,
        "bestDevGroupedNDCG@10": best_ndcg,
        "epochsRun": len(history),
        "durationSeconds": duration_seconds,
        "device": str(device),
        "smoke": args.smoke,
        "history": history,
    }
    manifest = create_training_manifest(
        dataset=dataset,
        split=split,
        train_groups_path=Path(args.train_groups),
        dev_groups_path=Path(args.dev_groups),
        output_model_dir=merged_dir,
        model_id=args.model_id,
        hyperparameters=hyperparameters,
        training_result=training_result,
        cloud_cost_cny=args.cloud_cost_cny,
        hardware=args.hardware,
    )
    manifest_sha256 = write_manifest(manifest, merged_dir / "training-manifest.json")
    result = {
        "modelPath": str(merged_dir.resolve()),
        "modelId": args.model_id,
        "manifestSha256": manifest_sha256,
        **training_result,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return result


def _evaluate_grouped_ndcg(torch, model, loader) -> float:
    model.eval()
    rows: list[list[float]] = []
    with torch.no_grad():
        for encoded, group_count in loader:
            logits = model(**encoded, return_dict=True).logits.view(
                group_count, 1 + DEFAULT_NEGATIVE_COUNT
            )
            rows.extend(logits.detach().float().cpu().tolist())
    return grouped_ndcg_at_10(rows)


def _set_seed(torch, seed: int) -> None:
    random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
    if hasattr(torch, "use_deterministic_algorithms"):
        torch.use_deterministic_algorithms(True, warn_only=True)


def _resolve_device(torch, requested: str):
    if requested != "auto":
        if requested == "cuda" and not torch.cuda.is_available():
            raise ValueError("CUDA requested but unavailable")
        if requested == "mps" and not torch.backends.mps.is_available():
            raise ValueError("MPS requested but unavailable")
        return torch.device(requested)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--train-groups", type=Path, required=True)
    parser.add_argument("--dev-groups", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--model-id", default="deepresearch-bge-reranker-scifact-lora-v1")
    parser.add_argument("--max-epochs", type=int, default=DEFAULT_MAX_EPOCHS)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--eval-batch-size", type=int, default=8)
    parser.add_argument("--max-length", type=int, default=DEFAULT_MAX_LENGTH)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--weight-decay", type=float, default=0.01)
    parser.add_argument("--warmup-ratio", type=float, default=0.10)
    parser.add_argument("--max-grad-norm", type=float, default=1.0)
    parser.add_argument("--early-stopping-patience", type=int, default=1)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda", "mps"), default="auto")
    parser.add_argument("--fp16", action="store_true")
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--cloud-cost-cny", type=float, default=0.0)
    parser.add_argument("--hardware", default=platform.platform())
    return parser


def main() -> None:
    run_training(build_parser().parse_args())


if __name__ == "__main__":
    main()
