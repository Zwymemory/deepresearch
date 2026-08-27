#!/usr/bin/env python3
"""Run external baselines on BEIR SciFact raw corpus/qrels.

Baselines:
- bm25_raw_corpus: pure-Python BM25 over original SciFact documents.
- sentence-transformers dense models: MiniLM / E5 / BGE.

Dense embeddings are cached under testdata/open/scifact/cache so interrupted
runs can resume without recomputing the corpus vectors.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
from collections import Counter, OrderedDict, defaultdict
from pathlib import Path
from typing import Iterable


TOKEN_RE = re.compile(r"[A-Za-z0-9]+")
DEFAULT_DENSE_MODELS = [
    "sentence-transformers/all-MiniLM-L6-v2",
    "intfloat/e5-small-v2",
    "BAAI/bge-small-en-v1.5",
]
MODEL_ALIASES = {
    "minilm": "sentence-transformers/all-MiniLM-L6-v2",
    "e5": "intfloat/e5-small-v2",
    "bge": "BAAI/bge-small-en-v1.5",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--k1", type=float, default=1.2)
    parser.add_argument("--b", type=float, default=0.75)
    parser.add_argument(
        "--methods",
        nargs="+",
        default=["bm25", "dense"],
        choices=["bm25", "dense"],
        help="Which baselines to run.",
    )
    parser.add_argument(
        "--dense-models",
        nargs="*",
        default=DEFAULT_DENSE_MODELS,
        help="Sentence-transformers model ids or aliases: minilm, e5, bge.",
    )
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--force", action="store_true", help="Recompute cached dense embeddings.")
    args = parser.parse_args()

    root = Path(args.root)
    raw_dir = root / "testdata" / "open" / "scifact" / "raw" / "scifact"
    result_dir = root / "testdata" / "open" / "scifact" / "results"
    cache_dir = root / "testdata" / "open" / "scifact" / "cache"
    result_dir.mkdir(parents=True, exist_ok=True)
    cache_dir.mkdir(parents=True, exist_ok=True)

    corpus = read_jsonl(raw_dir / "corpus.jsonl")
    queries = read_jsonl(raw_dir / "queries.jsonl")
    qrels = read_qrels(raw_dir / "qrels" / "test.tsv")
    eval_query_ids = [qid for qid in qrels.keys() if qid in queries]

    outputs: list[OrderedDict[str, object]] = []
    if "bm25" in args.methods:
        outputs.append(run_bm25(corpus, queries, qrels, eval_query_ids, args.top_k, args.k1, args.b, result_dir))

    if "dense" in args.methods:
        outputs.extend(run_dense_models(
            root=root,
            corpus=corpus,
            queries=queries,
            qrels=qrels,
            eval_query_ids=eval_query_ids,
            top_k=args.top_k,
            models=normalize_models(args.dense_models),
            batch_size=args.batch_size,
            force=args.force,
            result_dir=result_dir,
            cache_dir=cache_dir,
        ))

    write_markdown(result_dir / "baseline-summary.md", outputs, root)
    for output in outputs:
        print_summary(output)
    print(f"Saved baseline summary to {result_dir / 'baseline-summary.md'}")


def run_bm25(corpus: OrderedDict[str, dict],
             queries: OrderedDict[str, dict],
             qrels: OrderedDict[str, set[str]],
             eval_query_ids: list[str],
             top_k: int,
             k1: float,
             b: float,
             result_dir: Path) -> OrderedDict[str, object]:
    bm25 = Bm25Index(corpus, k1=k1, b=b)
    cases = []
    for qid in eval_query_ids:
        relevant_doc_ids = qrels[qid]
        ranked = bm25.search(queries[qid]["text"], top_k)
        metrics = case_metrics([doc_id for doc_id, _ in ranked], relevant_doc_ids, top_k)
        cases.append(case_result(qid, queries[qid]["text"], relevant_doc_ids, ranked, metrics))

    output = baseline_output(
        method="bm25_raw_corpus",
        top_k=top_k,
        corpus_docs=len(corpus),
        cases=cases,
        params=OrderedDict(k1=k1, b=b),
    )
    save_json(result_dir / "baseline-bm25.json", output)
    return output


def run_dense_models(root: Path,
                     corpus: OrderedDict[str, dict],
                     queries: OrderedDict[str, dict],
                     qrels: OrderedDict[str, set[str]],
                     eval_query_ids: list[str],
                     top_k: int,
                     models: list[str],
                     batch_size: int,
                     force: bool,
                     result_dir: Path,
                     cache_dir: Path) -> list[OrderedDict[str, object]]:
    try:
        import numpy as np
        from sentence_transformers import SentenceTransformer
    except ImportError as exc:
        raise SystemExit(
            "Dense baselines require numpy and sentence-transformers. "
            "Install them in the baseline venv first."
        ) from exc

    outputs: list[OrderedDict[str, object]] = []
    corpus_doc_ids = list(corpus.keys())
    corpus_texts = [doc_text(corpus[doc_id]) for doc_id in corpus_doc_ids]
    query_texts = [queries[qid]["text"] for qid in eval_query_ids]

    for model_id in models:
        method = dense_method_name(model_id)
        print(f"Running dense baseline: {model_id}")
        model = SentenceTransformer(model_id)
        corpus_embeddings = cached_embeddings(
            np=np,
            model=model,
            model_id=model_id,
            kind="corpus",
            texts=[format_passage(model_id, text) for text in corpus_texts],
            cache_dir=cache_dir,
            batch_size=batch_size,
            force=force,
        )
        query_embeddings = cached_embeddings(
            np=np,
            model=model,
            model_id=model_id,
            kind="queries",
            texts=[format_query(model_id, text) for text in query_texts],
            cache_dir=cache_dir,
            batch_size=batch_size,
            force=force,
        )

        scores = query_embeddings @ corpus_embeddings.T
        cases = []
        for idx, qid in enumerate(eval_query_ids):
            row = scores[idx]
            top_indices = np.argpartition(-row, min(top_k, len(row) - 1))[:top_k]
            top_indices = top_indices[np.argsort(-row[top_indices])]
            ranked = [(corpus_doc_ids[i], float(row[i])) for i in top_indices]
            metrics = case_metrics([doc_id for doc_id, _ in ranked], qrels[qid], top_k)
            cases.append(case_result(qid, queries[qid]["text"], qrels[qid], ranked, metrics))

        output = baseline_output(
            method=method,
            top_k=top_k,
            corpus_docs=len(corpus),
            cases=cases,
            params=OrderedDict(model=model_id, batchSize=batch_size, normalizedEmbeddings=True),
        )
        save_json(result_dir / f"baseline-{method}.json", output)
        outputs.append(output)
    return outputs


class Bm25Index:
    def __init__(self, corpus: OrderedDict[str, dict], k1: float, b: float) -> None:
        self.k1 = k1
        self.b = b
        self.doc_ids = list(corpus.keys())
        self.doc_len: dict[str, int] = {}
        self.inverted: dict[str, list[tuple[str, int]]] = defaultdict(list)
        total_len = 0

        for doc_id, row in corpus.items():
            tf = Counter(tokenize(doc_text(row)))
            length = sum(tf.values())
            self.doc_len[doc_id] = length
            total_len += length
            for term, freq in tf.items():
                self.inverted[term].append((doc_id, freq))

        self.doc_count = len(self.doc_ids)
        self.avgdl = total_len / self.doc_count if self.doc_count else 0.0
        self.idf = {
            term: math.log(1.0 + (self.doc_count - len(postings) + 0.5) / (len(postings) + 0.5))
            for term, postings in self.inverted.items()
        }

    def search(self, query: str, top_k: int) -> list[tuple[str, float]]:
        scores: defaultdict[str, float] = defaultdict(float)
        for term in set(tokenize(query)):
            postings = self.inverted.get(term)
            if not postings:
                continue
            idf = self.idf[term]
            for doc_id, freq in postings:
                dl = self.doc_len[doc_id]
                denom = freq + self.k1 * (1.0 - self.b + self.b * dl / self.avgdl)
                scores[doc_id] += idf * (freq * (self.k1 + 1.0) / denom)
        return sorted(scores.items(), key=lambda item: (-item[1], item[0]))[:top_k]


def cached_embeddings(np, model, model_id: str, kind: str, texts: list[str],
                      cache_dir: Path, batch_size: int, force: bool):
    cache_path = cache_dir / f"{dense_method_name(model_id)}-{kind}.npy"
    if cache_path.exists() and not force:
        print(f"  loading cached {kind} embeddings: {cache_path}")
        return np.load(cache_path)
    print(f"  encoding {len(texts)} {kind} texts")
    embeddings = model.encode(
        texts,
        batch_size=batch_size,
        show_progress_bar=True,
        normalize_embeddings=True,
        convert_to_numpy=True,
    )
    np.save(cache_path, embeddings)
    return embeddings


def read_jsonl(path: Path) -> OrderedDict[str, dict]:
    rows: OrderedDict[str, dict] = OrderedDict()
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                item = json.loads(line)
                rows[str(item["_id"])] = item
    return rows


def read_qrels(path: Path) -> OrderedDict[str, set[str]]:
    rows: OrderedDict[str, set[str]] = OrderedDict()
    with path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            qid = str(row.get("query-id") or row.get("query_id") or "").strip()
            doc_id = str(row.get("corpus-id") or row.get("corpus_id") or "").strip()
            score = int(float(row.get("score") or 0))
            if qid and doc_id and score > 0:
                rows.setdefault(qid, set()).add(doc_id)
    return rows


def tokenize(text: str) -> list[str]:
    return [match.group(0).lower() for match in TOKEN_RE.finditer(text or "")]


def doc_text(row: dict) -> str:
    return f"{row.get('title', '')}. {row.get('text', '')}".strip()


def format_query(model_id: str, text: str) -> str:
    lowered = model_id.lower()
    if "e5" in lowered:
        return f"query: {text}"
    if "bge" in lowered:
        return f"Represent this sentence for searching relevant passages: {text}"
    return text


def format_passage(model_id: str, text: str) -> str:
    if "e5" in model_id.lower():
        return f"passage: {text}"
    return text


def case_metrics(ranked_doc_ids: list[str], relevant_doc_ids: set[str], top_k: int) -> OrderedDict[str, object]:
    relevant_ranks = [
        rank for rank, doc_id in enumerate(ranked_doc_ids[:top_k], start=1)
        if doc_id in relevant_doc_ids
    ]
    first_rank = relevant_ranks[0] if relevant_ranks else None
    dcg = sum(1.0 / log2(rank + 1.0) for rank in relevant_ranks)
    ideal_hits = min(len(relevant_doc_ids), top_k)
    idcg = sum(1.0 / log2(rank + 1.0) for rank in range(1, ideal_hits + 1))
    return OrderedDict(
        hit=bool(first_rank),
        rank=first_rank,
        recall=(len(set(ranked_doc_ids[:top_k]) & relevant_doc_ids) / len(relevant_doc_ids))
        if relevant_doc_ids else 0.0,
        mrr=(1.0 / first_rank) if first_rank else 0.0,
        ndcg=(dcg / idcg) if idcg else 0.0,
    )


def summarize(cases: list[OrderedDict[str, object]]) -> OrderedDict[str, object]:
    total = len(cases)
    hit_count = sum(1 for case in cases if case["hit"])
    return OrderedDict(
        hitCount=hit_count,
        hitRateAtK=round(hit_count / total, 4) if total else 0.0,
        recallAtK=round(sum(float(case["recall"]) for case in cases) / total, 4) if total else 0.0,
        mrr=round(sum(float(case["mrr"]) for case in cases) / total, 4) if total else 0.0,
        ndcgAtK=round(sum(float(case["ndcg"]) for case in cases) / total, 4) if total else 0.0,
    )


def case_result(qid: str, question: str, expected: Iterable[str],
                ranked: list[tuple[str, float]],
                metrics: OrderedDict[str, object]) -> OrderedDict[str, object]:
    return OrderedDict(
        id=f"scifact-{qid}",
        queryId=qid,
        question=question,
        expectedDocIds=sorted(expected),
        topDocs=[OrderedDict(docId=doc_id, score=round(score, 6)) for doc_id, score in ranked],
        **metrics,
    )


def baseline_output(method: str, top_k: int, corpus_docs: int,
                    cases: list[OrderedDict[str, object]],
                    params: OrderedDict[str, object]) -> OrderedDict[str, object]:
    return OrderedDict(
        dataset="BEIR SciFact",
        method=method,
        topK=top_k,
        corpusDocs=corpus_docs,
        totalCases=len(cases),
        params=params,
        metrics=summarize(cases),
        cases=cases,
    )


def write_markdown(path: Path, outputs: list[OrderedDict[str, object]], root: Path) -> None:
    rows = [(readable_method(output["method"]), output["metrics"]) for output in outputs]
    total_cases = outputs[0]["totalCases"] if outputs else ""
    corpus_docs = outputs[0]["corpusDocs"] if outputs else ""
    top_k = outputs[0]["topK"] if outputs else 10
    skipped_legacy = []
    for existing in sorted(path.parent.glob("baseline-*.json")):
        if existing.name in {f"baseline-{output['method']}.json" for output in outputs}:
            continue
        data = json.loads(existing.read_text(encoding="utf-8"))
        metrics = data.get("metrics", {})
        if "hitRateAtK" not in metrics:
            skipped_legacy.append(existing.name)
            continue
        rows.append((readable_method(data["method"]), metrics))

    ours_path = root / "testdata" / "open" / "scifact" / "results" / "full-scifact-eval.json"
    if ours_path.exists():
        ours = json.loads(ours_path.read_text(encoding="utf-8"))
        same_experiment_shape = (
            ours.get("topK") == top_k
            and ours.get("totalCases") == total_cases
        )
        for route, metrics in ours.get("metrics", {}).items():
            if same_experiment_shape and "hitRateAtK" in metrics:
                rows.append((f"DeepResearch {route}", metrics))
            else:
                skipped_legacy.append(f"{ours_path.name}:{route}")

    seen = set()
    deduped = []
    for name, metrics in rows:
        if name not in seen:
            deduped.append((name, metrics))
            seen.add(name)

    lines = [
        "# SciFact Baseline Summary",
        "",
        f"- Corpus docs: {corpus_docs}",
        f"- Test queries: {total_cases}",
        f"- topK: {top_k}",
        "",
        f"| Method | HitCount | HitRate@{top_k} | Recall@{top_k} | MRR | NDCG@{top_k} |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for name, metrics in deduped:
        lines.append(
            f"| {name} | {metrics.get('hitCount')} | {metrics.get('hitRateAtK')} | "
            f"{metrics.get('recallAtK')} | "
            f"{metrics.get('mrr')} | {metrics.get('ndcgAtK')} |"
        )
    lines.append("")
    if skipped_legacy:
        lines.append(
            "> Skipped legacy or mismatched result rows: "
            + ", ".join(sorted(set(skipped_legacy)))
            + ". Re-run them with the current HitRate/Recall schema before comparing."
        )
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def save_json(path: Path, output: OrderedDict[str, object]) -> None:
    path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {output['method']} JSON to {path}")


def normalize_models(models: list[str]) -> list[str]:
    return [MODEL_ALIASES.get(model.lower(), model) for model in models]


def dense_method_name(model_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", model_id).strip("_").lower()


def readable_method(method: str) -> str:
    mapping = {
        "bm25_raw_corpus": "BM25 raw corpus",
        "sentence-transformers_all-minilm-l6-v2": "MiniLM all-MiniLM-L6-v2",
        "intfloat_e5-small-v2": "E5 small v2",
        "baai_bge-small-en-v1.5": "BGE small en v1.5",
    }
    return mapping.get(method, method)


def print_summary(output: OrderedDict[str, object]) -> None:
    metrics = output["metrics"]
    print(f"dataset={output['dataset']} method={output['method']} topK={output['topK']} totalCases={output['totalCases']}")
    print("method,hitCount,HitRate@K,Recall@K,MRR,NDCG@K")
    print(
        f"{output['method']},{metrics['hitCount']},"
        f"{metrics['hitRateAtK']},{metrics['recallAtK']},{metrics['mrr']},{metrics['ndcgAtK']}"
    )


def log2(value: float) -> float:
    return math.log(value) / math.log(2.0)


if __name__ == "__main__":
    main()
