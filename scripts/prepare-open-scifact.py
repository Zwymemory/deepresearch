#!/usr/bin/env python3
"""
Prepare a small BEIR SciFact subset for DeepResearch retrieval evaluation.

The script downloads the official BEIR SciFact zip, converts a bounded subset
into Markdown documents for the existing W5 ingestion pipeline, and writes
JSONL eval cases that use document-level gold labels.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import urllib.request
import zipfile
from collections import OrderedDict
from pathlib import Path


DEFAULT_URL = "https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets/scifact.zip"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--max-docs", type=int, default=300)
    parser.add_argument("--max-queries", type=int, default=50)
    parser.add_argument("--full", action="store_true", help="Use the full SciFact corpus and all test qrels queries.")
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    args = parser.parse_args()

    root = Path(args.root)
    raw_dir = root / "testdata" / "open" / "scifact" / "raw"
    out_dir = root / "testdata" / "open" / "scifact"
    kb_dir = out_dir / "kb"
    eval_path = root / "testdata" / "eval" / "open-scifact.jsonl"
    manifest_path = out_dir / "manifest.json"

    raw_dir.mkdir(parents=True, exist_ok=True)
    zip_path = raw_dir / "scifact.zip"
    extracted_dir = raw_dir / "scifact"
    if not zip_path.exists():
        print(f"Downloading {args.url}")
        urllib.request.urlretrieve(args.url, zip_path)

    if not extracted_dir.exists():
        print(f"Extracting {zip_path}")
        with zipfile.ZipFile(zip_path) as zf:
            zf.extractall(raw_dir)

    corpus = read_jsonl(extracted_dir / "corpus.jsonl")
    queries = read_jsonl(extracted_dir / "queries.jsonl")
    qrels = read_qrels(extracted_dir / "qrels" / "test.tsv")
    max_docs = len(corpus) if args.full else args.max_docs
    max_queries = len(qrels) if args.full else args.max_queries

    selected_query_ids, required_doc_ids = select_cases(
        qrels=qrels,
        queries=queries,
        corpus=corpus,
        max_docs=max_docs,
        max_queries=max_queries,
    )
    if not selected_query_ids:
        raise SystemExit("No SciFact eval cases selected. Increase --max-docs or check the dataset files.")

    doc_ids = select_documents(corpus, required_doc_ids, max_docs)
    if kb_dir.exists():
        for path in kb_dir.glob("*.md"):
            path.unlink()
    kb_dir.mkdir(parents=True, exist_ok=True)
    eval_path.parent.mkdir(parents=True, exist_ok=True)

    doc_filename = {}
    for doc_id in doc_ids:
        filename = f"scifact-{safe_id(doc_id)}.md"
        doc_filename[doc_id] = filename
        item = corpus[doc_id]
        title = clean_text(item.get("title") or f"SciFact document {doc_id}")
        text = clean_text(item.get("text") or "")
        markdown = f"# BEIR SciFact {doc_id}\n\n## Title\n{title}\n\n## Abstract\n{text}\n"
        (kb_dir / filename).write_text(markdown, encoding="utf-8")

    with eval_path.open("w", encoding="utf-8") as f:
        for qid in selected_query_ids:
            relevant = [doc_filename[doc_id] for doc_id in qrels[qid] if doc_id in doc_filename]
            case = OrderedDict(
                id=f"scifact-{safe_id(qid)}",
                question=clean_text(queries[qid].get("text") or ""),
                expectedTitle="",
                expectedTerms=[],
                expectedChunkKeys=[],
                expectedDocKeys=relevant,
                type="open_scifact",
                difficulty="open",
            )
            f.write(json.dumps(case, ensure_ascii=False) + "\n")

    manifest = OrderedDict(
        dataset="BEIR SciFact",
        sourceUrl=args.url,
        license="Follow the upstream BEIR/SciFact dataset license.",
        maxDocs=max_docs,
        maxQueries=max_queries,
        selectedDocs=len(doc_ids),
        selectedQueries=len(selected_query_ids),
        corpusDocs=len(corpus),
        testQrelsQueries=len(qrels),
        kbDir=str(kb_dir.relative_to(root)),
        evalFile=str(eval_path.relative_to(root)),
        matchMode="strict_doc_key",
    )
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def read_jsonl(path: Path) -> OrderedDict[str, dict]:
    rows: OrderedDict[str, dict] = OrderedDict()
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            item = json.loads(line)
            rows[str(item["_id"])] = item
    return rows


def read_qrels(path: Path) -> OrderedDict[str, list[str]]:
    qrels: OrderedDict[str, list[str]] = OrderedDict()
    with path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            qid = str(row.get("query-id") or row.get("query_id") or "").strip()
            doc_id = str(row.get("corpus-id") or row.get("corpus_id") or "").strip()
            score = int(float(row.get("score") or 0))
            if qid and doc_id and score > 0:
                qrels.setdefault(qid, []).append(doc_id)
    return qrels


def select_cases(
    qrels: OrderedDict[str, list[str]],
    queries: OrderedDict[str, dict],
    corpus: OrderedDict[str, dict],
    max_docs: int,
    max_queries: int,
) -> tuple[list[str], set[str]]:
    selected = []
    required: set[str] = set()
    for qid, doc_ids in qrels.items():
        if qid not in queries:
            continue
        relevant = [doc_id for doc_id in doc_ids if doc_id in corpus]
        if not relevant:
            continue
        next_required = required | set(relevant)
        if len(next_required) > max_docs:
            continue
        selected.append(qid)
        required = next_required
        if len(selected) >= max_queries:
            break
    return selected, required


def select_documents(corpus: OrderedDict[str, dict], required_doc_ids: set[str], max_docs: int) -> list[str]:
    docs = [doc_id for doc_id in corpus.keys() if doc_id in required_doc_ids]
    for doc_id in corpus.keys():
        if len(docs) >= max_docs:
            break
        if doc_id not in required_doc_ids:
            docs.append(doc_id)
    return docs


def safe_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", str(value)).strip("_") or "unknown"


def clean_text(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


if __name__ == "__main__":
    main()
