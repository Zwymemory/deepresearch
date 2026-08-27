"""Hard-negative schemas and mining pipeline.

Candidate runs (BM25 and one or more dense retrievers) are fused with RRF.
Optionally, the pinned base Cross-Encoder reorders that pool before positives
are removed and the top five negatives are selected.  A local BM25 provider is
used only when no external run contains the query.
"""

from __future__ import annotations

import argparse
import json
import math
import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping, Protocol, Sequence

from .config import (
    BASE_MODEL_ID,
    BASE_MODEL_REVISION,
    DEFAULT_MAX_LENGTH,
    DEFAULT_NEGATIVE_COUNT,
)
from .scifact import SciFactDataset, SciFactDocument, build_split, id_sort_key, load_scifact
from .text_format import format_pair


SCHEMA_VERSION = 1
_TOKEN = re.compile(r"[A-Za-z0-9]+")


@dataclass(frozen=True)
class CandidateEvidence:
    doc_id: str
    source_ranks: Mapping[str, int]
    rrf_score: float
    base_reranker_score: float | None = None


@dataclass(frozen=True)
class TrainingDocument:
    doc_id: str
    title: str
    content: str
    evidence: CandidateEvidence | None = None

    @classmethod
    def from_scifact(
        cls, document: SciFactDocument, evidence: CandidateEvidence | None = None
    ) -> "TrainingDocument":
        return cls(document.doc_id, document.title, document.text, evidence)


@dataclass(frozen=True)
class TrainingGroup:
    query_id: str
    query: str
    positive: TrainingDocument
    negatives: tuple[TrainingDocument, ...]
    schema_version: int = field(default=SCHEMA_VERSION)

    def validate(self, negative_count: int = DEFAULT_NEGATIVE_COUNT) -> None:
        if self.schema_version != SCHEMA_VERSION:
            raise ValueError(f"unsupported schemaVersion={self.schema_version}")
        if not self.query_id or not self.query.strip():
            raise ValueError("queryId and query are required")
        if not self.positive.doc_id:
            raise ValueError("positive docId is required")
        if len(self.negatives) != negative_count:
            raise ValueError(f"expected {negative_count} negatives, got {len(self.negatives)}")
        negative_ids = [document.doc_id for document in self.negatives]
        if any(not doc_id for doc_id in negative_ids):
            raise ValueError("negative docIds are required")
        if self.positive.doc_id in negative_ids:
            raise ValueError("positive document is also present as a negative")
        if len(set(negative_ids)) != len(negative_ids):
            raise ValueError("negative document ids must be unique")

    def to_dict(self) -> dict[str, object]:
        self.validate()
        return {
            "schemaVersion": self.schema_version,
            "queryId": self.query_id,
            "query": self.query,
            "positive": _document_dict(self.positive),
            "negatives": [_document_dict(document) for document in self.negatives],
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, object]) -> "TrainingGroup":
        group = cls(
            schema_version=int(value.get("schemaVersion") or 0),
            query_id=str(value.get("queryId") or ""),
            query=str(value.get("query") or ""),
            positive=_parse_document(_mapping(value.get("positive"), "positive")),
            negatives=tuple(
                _parse_document(_mapping(item, "negative"))
                for item in _sequence(value.get("negatives"), "negatives")
            ),
        )
        group.validate()
        return group


class CandidateScorer(Protocol):
    """Pluggable base-reranker interface; implementations return one score per doc."""

    def score(self, query: str, documents: Sequence[SciFactDocument]) -> Sequence[float]: ...


def load_retrieval_run(path: str | Path) -> dict[str, list[str]]:
    """Load either the project's baseline JSON or the documented JSONL run schema.

    JSONL schema: {"queryId": "...", "rankedDocIds": ["d1", "d2", ...]}
    Project baseline schema: {"cases": [{"queryId": "...", "topDocs": [...]}]}
    """

    source = Path(path)
    if source.suffix.lower() == ".jsonl":
        result: dict[str, list[str]] = {}
        with source.open(encoding="utf-8") as rows:
            for line_number, line in enumerate(rows, start=1):
                if not line.strip():
                    continue
                row = json.loads(line)
                qid = str(row.get("queryId") or "")
                ranked = [str(doc_id) for doc_id in row.get("rankedDocIds") or []]
                if not qid or not ranked:
                    raise ValueError(f"invalid retrieval row at {source}:{line_number}")
                result[qid] = _deduplicate(ranked)
        return result

    value = json.loads(source.read_text(encoding="utf-8"))
    result = {}
    for case in value.get("cases") or []:
        qid = str(case.get("queryId") or "")
        ranked = [str(document.get("docId")) for document in case.get("topDocs") or []]
        if qid and ranked:
            result[qid] = _deduplicate(ranked)
    if not result:
        raise ValueError(f"no candidate rankings found in {source}")
    return result


def reciprocal_rank_fusion(
    rankings: Mapping[str, Sequence[str]], *, rrf_k: int = 60
) -> list[CandidateEvidence]:
    if rrf_k <= 0:
        raise ValueError("rrf_k must be positive")
    scores: defaultdict[str, float] = defaultdict(float)
    ranks: dict[str, dict[str, int]] = defaultdict(dict)
    for source_name in sorted(rankings):
        for rank, doc_id in enumerate(_deduplicate(rankings[source_name]), start=1):
            scores[doc_id] += 1.0 / (rrf_k + rank)
            ranks[doc_id][source_name] = rank
    return sorted(
        (
            CandidateEvidence(doc_id, dict(sorted(ranks[doc_id].items())), score)
            for doc_id, score in scores.items()
        ),
        key=lambda item: (-item.rrf_score, id_sort_key(item.doc_id)),
    )


def mine_training_groups(
    dataset: SciFactDataset,
    query_ids: Sequence[str],
    retrieval_runs: Mapping[str, Mapping[str, Sequence[str]]],
    *,
    scorer: CandidateScorer | None = None,
    negative_count: int = DEFAULT_NEGATIVE_COUNT,
    candidate_pool_size: int = 20,
) -> list[TrainingGroup]:
    if negative_count <= 0 or candidate_pool_size < negative_count:
        raise ValueError("candidate_pool_size must be at least negative_count > 0")
    local_bm25 = Bm25CandidateProvider(dataset.corpus)
    groups: list[TrainingGroup] = []

    for qid in query_ids:
        if qid not in dataset.queries:
            raise ValueError(f"unknown query id: {qid}")
        query = dataset.queries[qid]
        positives = set(dataset.train_qrels.get(qid) or dataset.test_qrels.get(qid) or ())
        if not positives:
            continue
        per_source = {
            name: list(run[qid])
            for name, run in retrieval_runs.items()
            if qid in run
        }
        if not per_source:
            per_source = {"bm25_local": local_bm25.search(query, candidate_pool_size + len(positives))}
        fused = reciprocal_rank_fusion(per_source)[:candidate_pool_size]
        fused = [candidate for candidate in fused if candidate.doc_id in dataset.corpus]

        if scorer and fused:
            documents = [dataset.corpus[candidate.doc_id] for candidate in fused]
            scores = list(scorer.score(query, documents))
            if len(scores) != len(fused):
                raise ValueError("CandidateScorer returned the wrong number of scores")
            fused = [
                CandidateEvidence(
                    candidate.doc_id,
                    candidate.source_ranks,
                    candidate.rrf_score,
                    float(score),
                )
                for candidate, score in zip(fused, scores)
            ]
            fused.sort(
                key=lambda item: (
                    -(item.base_reranker_score if item.base_reranker_score is not None else -math.inf),
                    -item.rrf_score,
                    id_sort_key(item.doc_id),
                )
            )

        negatives = [candidate for candidate in fused if candidate.doc_id not in positives]
        if len(negatives) < negative_count:
            already_seen = positives | {candidate.doc_id for candidate in negatives}
            for doc_id in local_bm25.search(query, candidate_pool_size * 3):
                if doc_id in already_seen:
                    continue
                negatives.append(
                    CandidateEvidence(
                        doc_id=doc_id,
                        source_ranks={"bm25_local_fallback": len(negatives) + 1},
                        rrf_score=0.0,
                    )
                )
                already_seen.add(doc_id)
                if len(negatives) >= negative_count:
                    break
        if len(negatives) < negative_count:
            raise ValueError(f"not enough negatives for query {qid}")

        negative_documents = tuple(
            TrainingDocument.from_scifact(dataset.corpus[item.doc_id], item)
            for item in negatives[:negative_count]
        )
        # Every positive produces a 1+5 listwise group. Other positives remain
        # excluded from negatives, preventing false-negative labels.
        for positive_id in sorted(positives, key=id_sort_key):
            group = TrainingGroup(
                query_id=qid,
                query=query,
                positive=TrainingDocument.from_scifact(dataset.corpus[positive_id]),
                negatives=negative_documents,
            )
            group.validate(negative_count)
            groups.append(group)
    return groups


def write_groups(groups: Sequence[TrainingGroup], path: str | Path) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8") as output:
        for group in groups:
            output.write(json.dumps(group.to_dict(), ensure_ascii=False, sort_keys=True) + "\n")


def load_groups(path: str | Path) -> list[TrainingGroup]:
    groups: list[TrainingGroup] = []
    with Path(path).open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            try:
                groups.append(TrainingGroup.from_dict(json.loads(line)))
            except (TypeError, ValueError, json.JSONDecodeError) as exc:
                raise ValueError(f"invalid training group at {path}:{line_number}: {exc}") from exc
    return groups


class Bm25CandidateProvider:
    """Deterministic dependency-free fallback; not a substitute for dense+BM25 RRF."""

    def __init__(self, corpus: Mapping[str, SciFactDocument], k1: float = 1.2, b: float = 0.75):
        self.corpus = corpus
        self.k1 = k1
        self.b = b
        self.doc_lengths: dict[str, int] = {}
        self.postings: dict[str, list[tuple[str, int]]] = defaultdict(list)
        total_length = 0
        for doc_id, document in corpus.items():
            terms = Counter(_tokens(f"{document.title} {document.text}"))
            length = sum(terms.values())
            self.doc_lengths[doc_id] = length
            total_length += length
            for term, frequency in terms.items():
                self.postings[term].append((doc_id, frequency))
        self.document_count = len(corpus)
        self.average_length = total_length / self.document_count if self.document_count else 0.0

    def search(self, query: str, limit: int) -> list[str]:
        scores: defaultdict[str, float] = defaultdict(float)
        for term in set(_tokens(query)):
            postings = self.postings.get(term, ())
            document_frequency = len(postings)
            if not document_frequency:
                continue
            inverse_frequency = math.log(
                1.0 + (self.document_count - document_frequency + 0.5) / (document_frequency + 0.5)
            )
            for doc_id, frequency in postings:
                length = self.doc_lengths[doc_id]
                normalizer = frequency + self.k1 * (
                    1.0 - self.b + self.b * length / self.average_length
                )
                scores[doc_id] += inverse_frequency * frequency * (self.k1 + 1.0) / normalizer
        ranked = sorted(scores, key=lambda doc_id: (-scores[doc_id], id_sort_key(doc_id)))
        if len(ranked) < limit:
            ranked.extend(
                doc_id for doc_id in sorted(self.corpus, key=id_sort_key) if doc_id not in scores
            )
        return ranked[:limit]


class TransformersCrossEncoderScorer:
    """Pinned base model scorer imported lazily to keep preparation tests offline."""

    def __init__(self, batch_size: int = 8, max_length: int = DEFAULT_MAX_LENGTH):
        import torch
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        self._torch = torch
        self._batch_size = batch_size
        self._max_length = max_length
        self._device = (
            "cuda" if torch.cuda.is_available()
            else "mps" if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available()
            else "cpu"
        )
        self._tokenizer = AutoTokenizer.from_pretrained(
            BASE_MODEL_ID, revision=BASE_MODEL_REVISION
        )
        self._model = AutoModelForSequenceClassification.from_pretrained(
            BASE_MODEL_ID, revision=BASE_MODEL_REVISION
        ).to(self._device)
        self._model.eval()

    def score(self, query: str, documents: Sequence[SciFactDocument]) -> Sequence[float]:
        pairs = [format_pair(query, document.title, document.text) for document in documents]
        scores: list[float] = []
        with self._torch.no_grad():
            for start in range(0, len(pairs), self._batch_size):
                encoded = self._tokenizer(
                    pairs[start:start + self._batch_size],
                    padding=True,
                    truncation=True,
                    max_length=self._max_length,
                    return_tensors="pt",
                )
                encoded = {name: tensor.to(self._device) for name, tensor in encoded.items()}
                logits = self._model(**encoded, return_dict=True).logits.view(-1)
                scores.extend(logits.detach().float().cpu().tolist())
        return scores


def _document_dict(document: TrainingDocument) -> dict[str, object]:
    value: dict[str, object] = {
        "docId": document.doc_id,
        "title": document.title,
        "content": document.content,
    }
    if document.evidence is not None:
        value["evidence"] = {
            "docId": document.evidence.doc_id,
            "sourceRanks": dict(sorted(document.evidence.source_ranks.items())),
            "rrfScore": document.evidence.rrf_score,
            "baseRerankerScore": document.evidence.base_reranker_score,
        }
    return value


def _parse_document(value: Mapping[str, object]) -> TrainingDocument:
    evidence_value = value.get("evidence")
    evidence = None
    if evidence_value is not None:
        raw = _mapping(evidence_value, "evidence")
        raw_base_score = (
            raw.get("base_reranker_score")
            if raw.get("base_reranker_score") is not None
            else raw.get("baseRerankerScore")
        )
        evidence = CandidateEvidence(
            doc_id=str(raw.get("doc_id") or raw.get("docId") or value.get("docId") or ""),
            source_ranks={
                str(name): int(rank)
                for name, rank in _mapping(raw.get("source_ranks") or raw.get("sourceRanks") or {}, "sourceRanks").items()
            },
            rrf_score=float(raw.get("rrf_score") or raw.get("rrfScore") or 0.0),
            base_reranker_score=(
                float(raw_base_score)
                if raw_base_score is not None
                else None
            ),
        )
    return TrainingDocument(
        doc_id=str(value.get("docId") or ""),
        title=str(value.get("title") or ""),
        content=str(value.get("content") or ""),
        evidence=evidence,
    )


def _mapping(value: object, name: str) -> Mapping[str, object]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be an object")
    return value


def _sequence(value: object, name: str) -> Sequence[object]:
    if not isinstance(value, list):
        raise ValueError(f"{name} must be an array")
    return value


def _deduplicate(values: Sequence[str]) -> list[str]:
    return list(dict.fromkeys(str(value) for value in values if str(value)))


def _tokens(value: str) -> list[str]:
    return [match.group(0).lower() for match in _TOKEN.finditer(value or "")]


def _parse_run_argument(value: str) -> tuple[str, Path]:
    name, separator, raw_path = value.partition("=")
    if not separator or not name.strip() or not raw_path.strip():
        raise argparse.ArgumentTypeError("candidate run must use NAME=/path/to/run.json")
    return name.strip(), Path(raw_path).expanduser()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--split", choices=("train", "dev"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--candidate-run",
        action="append",
        type=_parse_run_argument,
        default=[],
        metavar="NAME=PATH",
        help="Repeat for BM25 and dense candidate rankings; they are RRF-fused.",
    )
    parser.add_argument("--base-rerank", action="store_true")
    parser.add_argument("--candidate-pool-size", type=int, default=20)
    args = parser.parse_args()

    dataset = load_scifact(args.data_dir)
    split = build_split(dataset)
    query_ids = split.train_query_ids if args.split == "train" else split.dev_query_ids
    runs = {name: load_retrieval_run(path) for name, path in args.candidate_run}
    scorer = TransformersCrossEncoderScorer() if args.base_rerank else None
    groups = mine_training_groups(
        dataset,
        query_ids,
        runs,
        scorer=scorer,
        candidate_pool_size=args.candidate_pool_size,
    )
    write_groups(groups, args.output)
    print(json.dumps({"split": args.split, "queries": len(query_ids), "groups": len(groups)}))


if __name__ == "__main__":
    main()
