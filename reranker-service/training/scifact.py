"""SciFact loading, leakage audit, and deterministic grouped train/dev split.

The official BEIR SciFact train qrels contain two claims whose normalized text
also appears in the test qrels.  We remove the train-side copies before any
training.  Queries sharing a positive document are kept in the same split so a
paper cannot become a positive label in both train and validation.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Mapping

from .config import DEFAULT_SEED


_NON_WORD = re.compile(r"[\W_]+", flags=re.UNICODE)


@dataclass(frozen=True)
class SciFactDocument:
    doc_id: str
    title: str
    text: str


@dataclass(frozen=True)
class SciFactDataset:
    corpus: Mapping[str, SciFactDocument]
    queries: Mapping[str, str]
    train_qrels: Mapping[str, frozenset[str]]
    test_qrels: Mapping[str, frozenset[str]]
    data_dir: Path


@dataclass(frozen=True)
class LeakageMatch:
    normalized_query: str
    train_query_ids: tuple[str, ...]
    test_query_ids: tuple[str, ...]


@dataclass(frozen=True)
class SciFactSplit:
    train_query_ids: tuple[str, ...]
    dev_query_ids: tuple[str, ...]
    test_query_ids: tuple[str, ...]
    strict_test_query_ids: tuple[str, ...]
    removed_train_query_ids: tuple[str, ...]
    leakage_matches: tuple[LeakageMatch, ...]
    seed: int

    def validate(self, dataset: SciFactDataset) -> None:
        train = set(self.train_query_ids)
        dev = set(self.dev_query_ids)
        test = set(self.test_query_ids)
        if train & dev or train & test or dev & test:
            raise ValueError("query ids overlap across train/dev/test")

        train_norm = {normalize_query(dataset.queries[qid]) for qid in train}
        dev_norm = {normalize_query(dataset.queries[qid]) for qid in dev}
        test_norm = {normalize_query(dataset.queries[qid]) for qid in test}
        if train_norm & dev_norm or train_norm & test_norm or dev_norm & test_norm:
            raise ValueError("normalized query text overlaps across train/dev/test")

        train_docs = positive_documents(dataset.train_qrels, train)
        dev_docs = positive_documents(dataset.train_qrels, dev)
        if train_docs & dev_docs:
            raise ValueError("positive documents overlap across train/dev")

        cleaned_docs = train_docs | dev_docs
        expected_strict = {
            qid for qid in test
            if set(dataset.test_qrels[qid]).isdisjoint(cleaned_docs)
        }
        if set(self.strict_test_query_ids) != expected_strict:
            raise ValueError("strict test ids do not match the no-seen-positive definition")

    def to_dict(self, dataset: SciFactDataset) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "dataset": "BEIR SciFact",
            "seed": self.seed,
            "definition": {
                "queryLeakage": "casefold + punctuation/whitespace normalization",
                "grouping": "queries connected by any shared positive document stay together",
                "strictTest": "every positive document is absent from cleaned train+dev positives",
            },
            "counts": {
                "corpusDocuments": len(dataset.corpus),
                "rawTrainQueries": len(dataset.train_qrels),
                "removedTrainQueries": len(self.removed_train_query_ids),
                "trainQueries": len(self.train_query_ids),
                "devQueries": len(self.dev_query_ids),
                "testQueries": len(self.test_query_ids),
                "strictTestQueries": len(self.strict_test_query_ids),
            },
            "removedTrainQueryIds": list(self.removed_train_query_ids),
            "leakageMatches": [asdict(match) for match in self.leakage_matches],
            "splitDigests": {
                "train": digest_ids(self.train_query_ids),
                "dev": digest_ids(self.dev_query_ids),
                "test": digest_ids(self.test_query_ids),
                "strictTest": digest_ids(self.strict_test_query_ids),
            },
            "sourceSha256": source_hashes(dataset.data_dir),
        }


def normalize_query(value: str) -> str:
    return _NON_WORD.sub(" ", (value or "").casefold()).strip()


def load_scifact(data_dir: str | Path) -> SciFactDataset:
    root = Path(data_dir)
    corpus_rows = _read_jsonl(root / "corpus.jsonl")
    query_rows = _read_jsonl(root / "queries.jsonl")
    corpus = {
        doc_id: SciFactDocument(
            doc_id=doc_id,
            title=str(row.get("title") or ""),
            text=str(row.get("text") or ""),
        )
        for doc_id, row in corpus_rows.items()
    }
    queries = {qid: str(row.get("text") or "") for qid, row in query_rows.items()}
    train_qrels = _read_qrels(root / "qrels" / "train.tsv")
    test_qrels = _read_qrels(root / "qrels" / "test.tsv")
    return SciFactDataset(corpus, queries, train_qrels, test_qrels, root)


def build_split(
    dataset: SciFactDataset,
    *,
    dev_size: int = 104,
    seed: int = DEFAULT_SEED,
) -> SciFactSplit:
    train_by_text: dict[str, list[str]] = defaultdict(list)
    test_by_text: dict[str, list[str]] = defaultdict(list)
    for qid in dataset.train_qrels:
        train_by_text[normalize_query(dataset.queries[qid])].append(qid)
    for qid in dataset.test_qrels:
        test_by_text[normalize_query(dataset.queries[qid])].append(qid)

    overlaps = sorted(set(train_by_text) & set(test_by_text))
    matches = tuple(
        LeakageMatch(
            normalized_query=text,
            train_query_ids=tuple(sorted(train_by_text[text], key=id_sort_key)),
            test_query_ids=tuple(sorted(test_by_text[text], key=id_sort_key)),
        )
        for text in overlaps
    )
    removed = {qid for match in matches for qid in match.train_query_ids}
    clean_query_ids = [qid for qid in dataset.train_qrels if qid not in removed]
    components = _positive_document_components(clean_query_ids, dataset.train_qrels)
    dev_components = _choose_components(components, dev_size, seed)
    dev_ids = {qid for component in dev_components for qid in component}
    train_ids = set(clean_query_ids) - dev_ids
    test_ids = set(dataset.test_qrels)
    clean_positive_docs = positive_documents(dataset.train_qrels, train_ids | dev_ids)
    strict_test_ids = {
        qid for qid in test_ids
        if set(dataset.test_qrels[qid]).isdisjoint(clean_positive_docs)
    }

    split = SciFactSplit(
        train_query_ids=tuple(sorted(train_ids, key=id_sort_key)),
        dev_query_ids=tuple(sorted(dev_ids, key=id_sort_key)),
        test_query_ids=tuple(sorted(test_ids, key=id_sort_key)),
        strict_test_query_ids=tuple(sorted(strict_test_ids, key=id_sort_key)),
        removed_train_query_ids=tuple(sorted(removed, key=id_sort_key)),
        leakage_matches=matches,
        seed=seed,
    )
    split.validate(dataset)
    return split


def positive_documents(qrels: Mapping[str, frozenset[str]], query_ids: Iterable[str]) -> set[str]:
    return {doc_id for qid in query_ids for doc_id in qrels[qid]}


def write_split_artifacts(split: SciFactSplit, dataset: SciFactDataset, output_dir: str | Path) -> None:
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    for name, ids in (
        ("train", split.train_query_ids),
        ("dev", split.dev_query_ids),
        ("test", split.test_query_ids),
        ("strict-test", split.strict_test_query_ids),
    ):
        path = output / f"{name}-query-ids.jsonl"
        with path.open("w", encoding="utf-8") as target:
            for qid in ids:
                target.write(json.dumps({"queryId": qid}, ensure_ascii=False) + "\n")
    _write_json(output / "split-manifest.json", split.to_dict(dataset))


def digest_ids(ids: Iterable[str]) -> str:
    payload = "\n".join(sorted(ids, key=id_sort_key)).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def source_hashes(data_dir: Path) -> dict[str, str]:
    paths = {
        "corpus": data_dir / "corpus.jsonl",
        "queries": data_dir / "queries.jsonl",
        "trainQrels": data_dir / "qrels" / "train.tsv",
        "testQrels": data_dir / "qrels" / "test.tsv",
    }
    return {name: _sha256(path) for name, path in paths.items()}


def id_sort_key(value: str) -> tuple[int, int | str]:
    return (0, int(value)) if str(value).isdigit() else (1, str(value))


def _positive_document_components(
    query_ids: Iterable[str], qrels: Mapping[str, frozenset[str]]
) -> list[tuple[str, ...]]:
    ids = list(query_ids)
    parent = {qid: qid for qid in ids}

    def find(qid: str) -> str:
        while parent[qid] != qid:
            parent[qid] = parent[parent[qid]]
            qid = parent[qid]
        return qid

    def union(left: str, right: str) -> None:
        left_root, right_root = find(left), find(right)
        if left_root != right_root:
            parent[right_root] = left_root

    by_document: dict[str, list[str]] = defaultdict(list)
    for qid in ids:
        for doc_id in qrels[qid]:
            by_document[doc_id].append(qid)
    for document_queries in by_document.values():
        for qid in document_queries[1:]:
            union(document_queries[0], qid)

    grouped: dict[str, list[str]] = defaultdict(list)
    for qid in ids:
        grouped[find(qid)].append(qid)
    return [tuple(sorted(group, key=id_sort_key)) for group in grouped.values()]


def _choose_components(
    components: list[tuple[str, ...]], target_size: int, seed: int
) -> tuple[tuple[str, ...], ...]:
    if target_size <= 0:
        raise ValueError("dev_size must be positive")

    ordered = sorted(
        components,
        key=lambda component: hashlib.sha256(
            f"{seed}:{','.join(component)}".encode("utf-8")
        ).hexdigest(),
    )
    # Exact deterministic subset sum. Values are component indices, and the
    # first path discovered in stable hash order wins.
    solutions: dict[int, tuple[int, ...]] = {0: ()}
    for index, component in enumerate(ordered):
        size = len(component)
        for total in sorted(tuple(solutions), reverse=True):
            candidate = total + size
            if candidate <= target_size and candidate not in solutions:
                solutions[candidate] = solutions[total] + (index,)
    if target_size not in solutions:
        sizes = sorted(len(component) for component in components)
        raise ValueError(f"cannot form an exact dev split of {target_size}; component sizes={sizes}")
    return tuple(ordered[index] for index in solutions[target_size])


def _read_jsonl(path: Path) -> dict[str, dict[str, object]]:
    rows: dict[str, dict[str, object]] = {}
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            row = json.loads(line)
            row_id = str(row.get("_id") or "")
            if not row_id:
                raise ValueError(f"missing _id at {path}:{line_number}")
            rows[row_id] = row
    return rows


def _read_qrels(path: Path) -> dict[str, frozenset[str]]:
    qrels: dict[str, set[str]] = defaultdict(set)
    with path.open(encoding="utf-8") as source:
        for row in csv.DictReader(source, delimiter="\t"):
            qid = str(row.get("query-id") or row.get("query_id") or "").strip()
            doc_id = str(row.get("corpus-id") or row.get("corpus_id") or "").strip()
            score = int(float(row.get("score") or 0))
            if qid and doc_id and score > 0:
                qrels[qid].add(doc_id)
    return {qid: frozenset(doc_ids) for qid, doc_ids in qrels.items()}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--dev-size", type=int, default=104)
    args = parser.parse_args()
    dataset = load_scifact(args.data_dir)
    split = build_split(dataset, dev_size=args.dev_size, seed=DEFAULT_SEED)
    write_split_artifacts(split, dataset, args.output_dir)
    print(json.dumps(split.to_dict(dataset), ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
