#!/usr/bin/env python3
"""Capture redacted project answer samples and score independent quality axes.

Only the Python standard library is needed. The saved probe is a separate debug
request: for a workflow it is a retrieval diagnostic, not its internal receipt.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

from paired_eval import label_ranks, normalize_text


HERE = Path(__file__).resolve().parent
TERMINAL = {"SUCCEEDED", "INSUFFICIENT_EVIDENCE", "FAILED", "CANCELLED"}
MARKER = re.compile(r"\[来源(\d+)]")
RAGFLOW_ID = re.compile(r"kb:ragflow:([^\s\]\[\"']+)")
JWT = re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save(path, data):
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def redacted_source(source):
    if not isinstance(source, str):
        return ""
    if source.startswith("kb:ragflow:"):
        return "kb:ragflow:sha256-" + digest(source.encode())[:16]
    return source


def redact_text(value):
    if not isinstance(value, str):
        return ""
    value = JWT.sub("[REDACTED_JWT]", value)
    value = EMAIL.sub("[REDACTED_EMAIL]", value)
    value = PHONE.sub("[REDACTED_PHONE]", value)
    return RAGFLOW_ID.sub(lambda match: redacted_source(match.group()), value)


def suite(path):
    holdout_path = Path(path).resolve()
    holdout = load(holdout_path)
    if holdout.get("schemaVersion") != 1 or len(holdout.get("cases", [])) != 8:
        raise ValueError("holdout requires exactly eight fixed cases")
    base_path = holdout_path.parent / holdout["baseManifest"]
    base = load(base_path)
    if len(base.get("cases", [])) != 29:
        raise ValueError("base manifest requires 29 project cases")
    cases = base["cases"] + holdout["cases"]
    ids = [case["id"] for case in cases]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate case IDs")
    for case in cases:
        if case.get("kind") == "positive" and not case.get("requiredFacts"):
            raise ValueError(case["id"] + " has no required facts")
    return {
        "cases": cases,
        "baseSha256": digest(base_path.read_bytes()),
        "holdoutSha256": digest(holdout_path.read_bytes()),
        "topK": base["topK"],
        "caseCount": len(cases),
    }


def http_json(url, token, payload=None, headers=None, timeout=90):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request_headers = {"Accept": "application/json"}
    if data is not None:
        request_headers["Content-Type"] = "application/json"
    if token:
        request_headers["Authorization"] = "Bearer " + token
    request_headers.update(headers or {})
    request = urllib.request.Request(url, data=data, headers=request_headers,
                                     method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"HTTP {error.code} at {urllib.parse.urlsplit(url).path}") from None


def source_exists(source, ragflow_url, ragflow_key):
    if not source.startswith("kb:ragflow:"):
        return None
    parts = source.split(":")
    if len(parts) != 5 or not ragflow_url or not ragflow_key:
        return None
    dataset, document, chunk = (urllib.parse.quote(part, safe="") for part in parts[2:])
    endpoint = (ragflow_url.rstrip("/") + "/api/v1/datasets/" + dataset
                + "/documents/" + document + "/chunks/" + chunk)
    try:
        result = http_json(endpoint, ragflow_key, timeout=15)
    except (OSError, ValueError, RuntimeError):
        return False
    data = result.get("data") or {}
    return (result.get("code") == 0 and data.get("id") == parts[4]
            and (data.get("doc_id") or data.get("document_id")) == parts[3])


def probe(base, token, question, top_k):
    start = time.perf_counter_ns()
    response = http_json(base + "/api/research/hybrid/debug", token,
                         {"question": question, "topK": top_k, "history": []})
    provider = response.get("provider")
    field = "compressedContext" if provider == "ragflow" else "rerankResult"
    entries = response.get(field)
    if provider not in ("ragflow", "legacy") or not isinstance(entries, list):
        raise ValueError("debug response has no recognized provider or entries")
    return {"provider": provider, "latencyMs": round((time.perf_counter_ns() - start) / 1e6, 3),
            "entries": [{"preview": redact_text(item.get("preview")),
                         "source": redacted_source(item.get("chunkKey"))}
                        for item in entries[:top_k]]}


def workflow_answer(base, token, question, timeout):
    key = "showcase-" + uuid.uuid4().hex
    accepted = http_json(base + "/api/research/workflows", token,
                         {"question": question, "requestedTools": ["kb_search"]},
                         {"Idempotency-Key": key}, timeout=timeout)
    run_id = accepted["runId"]
    deadline = time.monotonic() + timeout
    while True:
        view = http_json(base + "/api/research/workflows/" + urllib.parse.quote(run_id, safe=""),
                         token, timeout=30)
        if view.get("status") in TERMINAL:
            return view
        if time.monotonic() >= deadline:
            raise TimeoutError("workflow did not finish before evaluation deadline")
        time.sleep(0.5)


def capture_answer(mode, base, token, question, top_k, timeout):
    start = time.perf_counter_ns()
    if mode == "legacy-hybrid":
        value = http_json(base + "/api/research/hybrid", token,
                          {"question": question, "topK": top_k, "history": []}, timeout=timeout)
        status = "SUCCEEDED" if value.get("sources") else "INSUFFICIENT_EVIDENCE"
        sources = value.get("sources") or []
        citations = [{"source": "", "exists": None} for _ in sources]
        answer = value.get("answer") or ""
        usage = {}
        run_id = None
    else:
        value = workflow_answer(base, token, question, timeout)
        status = value.get("status")
        final = value.get("finalResponse") or {}
        source_ids = final.get("citations") or []
        citations = [{"source": redacted_source(source),
                      "exists": source_exists(source, os.getenv("SHOWCASE_RAGFLOW_URL"),
                                              os.getenv("RAGFLOW_API_KEY"))}
                     for source in source_ids]
        answer = final.get("answer") or ""
        usage = value.get("usage") or {}
        run_id = value.get("runId")
    return {"status": status, "runId": run_id,
            "latencyMs": round((time.perf_counter_ns() - start) / 1e6, 3),
            "answer": redact_text(answer), "citations": citations,
            "usage": {key: usage.get(key) for key in ("inputTokens", "outputTokens", "totalTokens",
                                                      "modelCalls", "toolCalls", "estimatedCost",
                                                      "currency", "durationMs") if usage.get(key) is not None}}


def collect(args):
    frozen = suite(args.holdout)
    conditions = load(args.conditions)
    required = ("codeSha", "model", "corpus", "budgets", "host", "testWindow")
    if any(not conditions.get(key) for key in required):
        raise ValueError("conditions require " + ", ".join(required))
    if conditions.get("mode") != args.mode:
        raise ValueError("conditions mode must match --mode")
    expected_provider = "legacy" if args.mode == "legacy-hybrid" else "ragflow"
    if conditions.get("retrievalProvider") != expected_provider:
        raise ValueError("conditions retrievalProvider does not match mode")
    if args.mode != "legacy-hybrid" and conditions.get("workflowEngine") != args.mode.split("-")[-1]:
        raise ValueError("conditions workflowEngine does not match mode")
    token = os.getenv("SHOWCASE_EVAL_TOKEN")
    if not token:
        raise ValueError("SHOWCASE_EVAL_TOKEN is required")
    base = args.base_url.rstrip("/")
    selected = set(args.case_id or [case["id"] for case in frozen["cases"]])
    unknown = selected - {case["id"] for case in frozen["cases"]}
    if unknown:
        raise ValueError("unknown case IDs: " + ", ".join(sorted(unknown)))
    samples = []
    for case in frozen["cases"]:
        if case["id"] not in selected:
            continue
        for repetition in range(1, args.repetitions + 1):
            diagnostic = probe(base, token, case["question"], frozen["topK"])
            if diagnostic["provider"] != expected_provider:
                raise ValueError(case["id"] + " debug provider mismatch")
            answer = capture_answer(args.mode, base, token, case["question"], frozen["topK"], args.timeout)
            samples.append({"caseId": case["id"], "repetition": repetition,
                            "retrievalProbe": diagnostic, "answerRun": answer})
            print(f"{args.mode} {case['id']} sample {repetition}: {answer['status']}", flush=True)
            save(args.output, {"schemaVersion": 1, "suite": frozen, "conditions": conditions,
                               "mode": args.mode, "samples": samples})
    return samples


def citation_contract(answer, citations):
    markers = [int(value) for value in MARKER.findall(answer)]
    return bool(markers) and bool(citations) and set(markers) == set(range(1, len(citations) + 1)) \
        and list(dict.fromkeys(markers)) == list(range(1, len(citations) + 1))


def score_one(case, sample, review=None):
    probe_result = sample["retrievalProbe"]
    run = sample["answerRun"]
    kind = case["kind"]
    answer = normalize_text(run["answer"])
    sources = run["citations"]
    markers_valid = citation_contract(answer, sources)
    result = {"caseId": case["id"], "repetition": sample["repetition"], "kind": kind,
              "status": run["status"], "latencyMs": run["latencyMs"],
              "retrievalLatencyMs": probe_result["latencyMs"],
              "citationContractValid": markers_valid,
              "citationSourceExists": all(item["exists"] is True for item in sources)
              if sources and all(item["exists"] is not None for item in sources) else None,
              "usage": run["usage"]}
    if kind == "positive":
        ranks = label_ranks(probe_result["entries"], case["requiredFacts"],
                            len(probe_result["entries"]))
        result["retrievalFactCoverage"] = sum(rank is not None for rank in ranks.values()) / len(ranks)
        result["retrievalFactRanks"] = ranks
        # Exact phrase matching is a conservative diagnostic; semantic paraphrases need review.
        exact = {label["id"]: any(normalize_text(phrase) in answer
                                   for phrase in label["evidencePhrases"])
                 for label in case["requiredFacts"]}
        result["answerExactPhraseCoverage"] = sum(exact.values()) / len(exact)
        result["answerExactPhraseHits"] = exact
        checked = (review or {}).get("factChecks")
        if isinstance(checked, dict) and set(checked) == set(exact) \
                and all(isinstance(value, bool) for value in checked.values()):
            result["reviewedAnswerFactCoverage"] = sum(checked.values()) / len(checked)
        else:
            result["reviewedAnswerFactCoverage"] = None
    else:
        if kind == "zero-evidence":
            result["retrievalNegativeClean"] = len(probe_result["entries"]) == 0
            result["automaticRefusalCorrect"] = (run["status"] == "INSUFFICIENT_EVIDENCE"
                                                 and not sources and not MARKER.search(answer))
        else:
            ranks = label_ranks(probe_result["entries"], case["denialEvidence"],
                                len(probe_result["entries"]))
            result["retrievalDenialEvidenceCoverage"] = sum(rank is not None for rank in ranks.values()) / len(ranks)
            contract = case["answerContract"]
            result["automaticRefusalCorrect"] = (any(normalize_text(phrase) in answer
                                                       for phrase in contract["mustContainAny"])
                                                 and not any(normalize_text(phrase) in answer
                                                             for phrase in contract["mustNotContain"])
                                                 and markers_valid)
        result["reviewedRefusalCorrect"] = (review or {}).get("refusalCorrect") \
            if isinstance((review or {}).get("refusalCorrect"), bool) else None
    result["reviewedCitationSupport"] = (review or {}).get("citationSupport") \
        if isinstance((review or {}).get("citationSupport"), bool) else None
    return result


def percentile95(values):
    return sorted(values)[math.ceil(0.95 * len(values)) - 1] if values else None


def score(args):
    captures = [load(path) for path in args.capture]
    if not captures:
        raise ValueError("at least one capture required")
    signature = (captures[0]["suite"]["baseSha256"], captures[0]["suite"]["holdoutSha256"])
    reviews = load(args.review) if args.review else {}
    results = []
    modes = {}
    for capture in captures:
        if (capture["suite"]["baseSha256"], capture["suite"]["holdoutSha256"]) != signature:
            raise ValueError("captures use different frozen labels")
        cases = {case["id"]: case for case in capture["suite"]["cases"]}
        mode = capture["mode"]
        if mode in modes:
            raise ValueError("duplicate mode capture; combine repetitions in one capture")
        modes[mode] = capture["conditions"]
        identities = set()
        for sample in capture["samples"]:
            identity = (sample["caseId"], sample["repetition"])
            if identity in identities or sample["caseId"] not in cases:
                raise ValueError("duplicate or unknown case sample")
            identities.add(identity)
            review = reviews.get(mode, {}).get(sample["caseId"], {}).get(str(sample["repetition"]))
            results.append({"mode": mode, **score_one(cases[sample["caseId"]], sample, review)})
    summaries = {}
    for mode in modes:
        rows = [row for row in results if row["mode"] == mode]
        positives = [row for row in rows if row["kind"] == "positive"]
        negatives = [row for row in rows if row["kind"] != "positive"]
        reviewed = [row["reviewedAnswerFactCoverage"] for row in positives
                    if row["reviewedAnswerFactCoverage"] is not None]
        reviewed_refusals = [row["reviewedRefusalCorrect"] for row in negatives
                             if row["reviewedRefusalCorrect"] is not None]
        reviewed_support = [row["reviewedCitationSupport"] for row in rows
                            if row["reviewedCitationSupport"] is not None]
        summaries[mode] = {
            "sampleCount": len(rows), "caseCount": len({row["caseId"] for row in rows}),
            "full37Once": len({row["caseId"] for row in rows}) == 37,
            "answerP95Ms": percentile95([row["latencyMs"] for row in rows]),
            "answerP95SampleCount": len(rows),
            "retrievalProbeP95Ms": percentile95([row["retrievalLatencyMs"] for row in rows]),
            "retrievalFactCoverageMean": sum(row["retrievalFactCoverage"] for row in positives) / len(positives)
            if positives else None,
            "reviewedAnswerFactCoverageMean": sum(reviewed) / len(reviewed) if reviewed else None,
            "reviewedPositiveSampleCount": len(reviewed),
            "reviewedRefusalsCorrect": sum(reviewed_refusals),
            "reviewedRefusalSampleCount": len(reviewed_refusals),
            "reviewedCitationsSupported": sum(reviewed_support),
            "reviewedCitationSampleCount": len(reviewed_support),
            "citationContractValid": sum(row["citationContractValid"] for row in rows),
            "citationSourceExistenceChecked": sum(row["citationSourceExists"] is not None for row in rows),
            "citationSourceExists": sum(row["citationSourceExists"] is True for row in rows),
            "automaticRefusalsCorrect": sum(row["automaticRefusalCorrect"] for row in negatives),
            "negativeSampleCount": len(negatives),
            "usageObserved": {key: [row["usage"].get(key) for row in rows]
                              for key in ("totalTokens", "modelCalls", "toolCalls", "estimatedCost")},
        }
    comparison_fields = ("model", "corpus", "budgets", "host")
    comparable = {field: len({json.dumps(value.get(field), ensure_ascii=False, sort_keys=True)
                              for value in modes.values()}) == 1 for field in comparison_fields}
    report = {"schemaVersion": 1, "suiteHashes": list(signature), "conditionsByMode": modes,
              "summaryByMode": summaries, "comparability": comparable, "samples": results,
              "interpretation": "Workflow retrieval probes are separate debug calls. Exact answer phrase scores are lower bounds; semantic fact and factual citation support require recorded review. Compare latency only under matched model, corpus and budgets."}
    save(args.output, report)
    return report


def review_template(args):
    capture = load(args.capture)
    cases = {case["id"]: case for case in capture["suite"]["cases"]}
    review = {capture["mode"]: {}}
    for sample in capture["samples"]:
        case = cases[sample["caseId"]]
        row = {"citationSupport": None, "note": "Review answer against the exact cited evidence."}
        if case["kind"] == "positive":
            row["factChecks"] = {item["id"]: None for item in case["requiredFacts"]}
        else:
            row["refusalCorrect"] = None
        review[capture["mode"]].setdefault(case["id"], {})[str(sample["repetition"])] = row
    save(args.output, review)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    collect_parser = commands.add_parser("collect")
    collect_parser.add_argument("--holdout", default=str(HERE / "showcase_holdout_cases.json"))
    collect_parser.add_argument("--mode", choices=("legacy-hybrid", "ragflow-dify", "ragflow-langgraph"), required=True)
    collect_parser.add_argument("--base-url", required=True)
    collect_parser.add_argument("--conditions", required=True)
    collect_parser.add_argument("--case-id", action="append")
    collect_parser.add_argument("--repetitions", type=int, default=1)
    collect_parser.add_argument("--timeout", type=int, default=180)
    collect_parser.add_argument("--output", required=True)
    score_parser = commands.add_parser("score")
    score_parser.add_argument("--capture", action="append", required=True)
    score_parser.add_argument("--review")
    score_parser.add_argument("--output", required=True)
    review_parser = commands.add_parser("review-template")
    review_parser.add_argument("--capture", required=True)
    review_parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command == "collect":
        if args.repetitions < 1 or args.timeout < 1:
            parser.error("repetitions and timeout must be positive")
        collect(args)
    elif args.command == "score":
        score(args)
    else:
        review_template(args)


if __name__ == "__main__":
    main()
