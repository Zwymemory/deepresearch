#!/usr/bin/env python3
"""Paired, retrieval-only legacy/RAGFlow migration measurement (stdlib only)."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROUTES = {"legacy": "rerankResult", "ragflow": "compressedContext"}
MAX_RESPONSE = 4 * 1024 * 1024


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def request_json(url: str, token: str | None = None, payload=None, method: str | None = None,
                 content_type: str = "application/json", timeout: float = 30):
    data = None if payload is None else (json.dumps(payload).encode() if content_type == "application/json" else payload)
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method or ("POST" if data else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            body = response.read(MAX_RESPONSE + 1)
            if len(body) > MAX_RESPONSE:
                raise ValueError("response exceeds 4 MiB")
            return json.loads(body)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"HTTP {error.code} at {urllib.parse.urlsplit(url).path}") from None


def validate_manifest(manifest):
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("manifest requires cases")
    ids = [case.get("id") for case in cases]
    if any(not isinstance(case_id, str) or not case_id for case_id in ids) or len(set(ids)) != len(ids):
        raise ValueError("case IDs must be nonempty and unique")
    for case in cases:
        if not isinstance(case.get("question"), str) or not case["question"].strip():
            raise ValueError(f"{case['id']}: question required")
        anchors = case.get("relevantAnchors")
        if not isinstance(anchors, list) or len(set(anchors)) != len(anchors) or any(
                not isinstance(anchor, str) or not anchor for anchor in anchors):
            raise ValueError(f"{case['id']}: relevantAnchors must be unique strings")
        if case.get("critical", False) and not anchors:
            raise ValueError(f"{case['id']}: a negative case cannot be critical")
    if not isinstance(manifest.get("topK"), int) or manifest["topK"] < 1:
        raise ValueError("positive topK required")


def base_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError("base URL must be HTTP(S) without embedded credentials")
    return url.rstrip("/")


def upload_fixture(base, token, fixture):
    boundary = "eval" + hashlib.sha256(fixture.read_bytes()).hexdigest()[:24]
    name = fixture.name.encode("ascii")
    body = (b"--" + boundary.encode() + b"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
            + name + b"\"\r\nContent-Type: text/markdown\r\n\r\n" + fixture.read_bytes()
            + b"\r\n--" + boundary.encode() + b"--\r\n")
    return request_json(base + "/api/kb/documents/file", token, body,
                        content_type="multipart/form-data; boundary=" + boundary)


def wait_for_ingestion(base, token, document_id, route, timeout_seconds=180):
    end = time.monotonic() + timeout_seconds
    while True:
        path = "/api/kb/documents/" + urllib.parse.quote(document_id, safe="")
        if route == "ragflow":
            path += "/ragflow-sync"  # polls/reconciles the mapping; kb_document DONE alone is insufficient
        detail = request_json(base + path, token)
        document = detail if route == "ragflow" else detail.get("document", {})
        status = document.get("status")
        if status in ("DONE", "FAILED"):
            return {"docId": document_id, "status": status, "chunkCount": document.get("chunkCount"),
                    "error": document.get("errorMessage")}
        if time.monotonic() >= end:
            return {"docId": document_id, "status": "TIMEOUT"}
        time.sleep(2)


def fetch_entries(base, token, case, top_k):
    start = time.perf_counter_ns()
    response = request_json(base + "/api/research/hybrid/debug", token,
                            {"question": case["question"], "topK": top_k, "history": []})
    duration_ms = (time.perf_counter_ns() - start) / 1_000_000
    return response, round(duration_ms, 3)


def citation_check(ragflow_base, api_key, chunk_key):
    parts = chunk_key.split(":")
    result = {"sourceId": "kb:" + chunk_key, "verified": False}
    if len(parts) != 4 or parts[0] != "ragflow" or any(not part for part in parts[1:]):
        result["error"] = "invalid chunkKey"
        return result
    dataset, document, chunk = (urllib.parse.quote(part, safe="") for part in parts[1:])
    url = f"{ragflow_base}/api/v1/datasets/{dataset}/documents/{document}/chunks/{chunk}"
    try:
        response = request_json(url, api_key)
        data = response.get("data", {})
        result["verified"] = (response.get("code") == 0 and isinstance(data, dict)
                              and data.get("id") == parts[3]
                              and (data.get("doc_id") or data.get("document_id")) == parts[2])
        if not result["verified"]:
            result["error"] = "RAGFlow returned mismatched chunk identifiers"
    except (OSError, ValueError, RuntimeError) as error:
        result["error"] = str(error)
    return result


def collect(args):
    manifest_path = Path(args.manifest)
    manifest = read_json(manifest_path)
    validate_manifest(manifest)
    fixture = manifest_path.parent / manifest["fixture"]
    if not fixture.is_file():
        raise ValueError("fixture is missing")
    conditions = read_json(Path(args.conditions))
    if not isinstance(conditions, dict) or any(not conditions.get(key) for key in
                                                ("legacyConfig", "ragflowConfig", "host", "corpus", "testWindow")):
        raise ValueError("conditions must record configurations, host, corpus and test window")
    project_doc_ids = conditions.get("projectDocumentIds", [])
    if manifest.get("kind") == "project" and (not isinstance(project_doc_ids, list) or not project_doc_ids
                                                or any(not isinstance(doc_id, str) or not doc_id for doc_id in project_doc_ids)):
        raise ValueError("project conditions require every projectDocumentId for mapping checks")
    bases = {"legacy": base_url(args.legacy_url), "ragflow": base_url(args.ragflow_url)}
    tokens = {"legacy": os.getenv("EVAL_LEGACY_TOKEN"), "ragflow": os.getenv("EVAL_RAGFLOW_TOKEN")}
    if args.ingest_fixture and any(not value for value in tokens.values()):
        raise ValueError("both EVAL_LEGACY_TOKEN and EVAL_RAGFLOW_TOKEN are required for ingestion")
    if manifest.get("kind") == "project" and not tokens["ragflow"]:
        raise ValueError("EVAL_RAGFLOW_TOKEN is required to verify project document mappings")
    ingestion = {}
    if args.ingest_fixture:
        for route in ROUTES:
            upload = upload_fixture(bases[route], tokens[route], fixture)
            doc_id = upload.get("docId")
            if not doc_id:
                raise ValueError(f"{route} upload did not return docId")
            ingestion[route] = wait_for_ingestion(bases[route], tokens[route], doc_id, route)
    project_mappings = []
    if manifest.get("kind") == "project":
        for doc_id in project_doc_ids:
            project_mappings.append(wait_for_ingestion(bases["ragflow"], tokens["ragflow"], doc_id, "ragflow"))
    runs = []
    top_k = manifest["topK"]
    for case in manifest["cases"]:
        # Alternate order to reduce route-order bias. Each query and topK are identical.
        order = list(ROUTES) if len(runs) % 2 == 0 else list(reversed(ROUTES))
        for route in order:
            for _ in range(args.warmup):
                fetch_entries(bases[route], tokens[route], case, top_k)
            samples = []
            for _ in range(args.repetitions):
                response, elapsed = fetch_entries(bases[route], tokens[route], case, top_k)
                if response.get("provider") != route:
                    raise ValueError(f"{route} endpoint reported provider={response.get('provider')!r}")
                field = ROUTES[route]
                entries = response.get(field)
                if not isinstance(entries, list):
                    raise ValueError(f"{route} response missing {field}")
                scores = response.get("similarityScores")
                if not isinstance(scores, dict):
                    raise ValueError(f"{route} response missing similarityScores")
                samples.append({"latencyMs": elapsed, "entries": [
                    {**{key: entry.get(key) for key in ("chunkKey", "docId", "chunkId", "title", "preview", "route")},
                     "similarityScore": scores.get(entry.get("chunkKey"))}
                    for entry in entries[:top_k]]})
            runs.append({"caseId": case["id"], "route": route, "samples": samples})
    verification = []
    if args.ragflow_api_url and os.getenv("RAGFLOW_API_KEY"):
        keys = {entry["chunkKey"] for run in runs if run["route"] == "ragflow"
                for entry in run["samples"][0]["entries"] if isinstance(entry["chunkKey"], str)}
        verification = [citation_check(base_url(args.ragflow_api_url), os.environ["RAGFLOW_API_KEY"], key)
                        for key in sorted(keys)]
    capture = {"schemaVersion": 1, "manifest": manifest, "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
               "conditions": conditions, "ingestion": ingestion or None,
               "projectMappings": project_mappings, "runs": runs,
               "citationChecks": verification,
               "citationCheckAttempted": bool(args.ragflow_api_url and os.getenv("RAGFLOW_API_KEY"))}
    write_json(Path(args.output), capture)
    return capture


def percentile95(values):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[math.ceil(0.95 * len(ordered)) - 1]


def score(capture):
    manifest = capture["manifest"]
    validate_manifest(manifest)
    top_k = manifest["topK"]
    indexed = {(run["caseId"], run["route"]): run for run in capture["runs"]}
    if len(indexed) != len(manifest["cases"]) * 2 or len(indexed) != len(capture["runs"]):
        raise ValueError("capture must have exactly one run per case and route")
    per_case = []
    aggregate = {route: {"hits": [], "recall": [], "mrr": [], "ndcg": [], "latencies": [],
                         "negativeClean": [], "unstable": []} for route in ROUTES}
    for case in manifest["cases"]:
        anchors = case["relevantAnchors"]
        result = {"id": case["id"], "critical": bool(case.get("critical")), "positive": bool(anchors)}
        for route in ROUTES:
            samples = indexed[(case["id"], route)]["samples"]
            if not samples:
                raise ValueError("every run needs measured samples")
            ranks_by_sample = []
            for sample in samples:
                entries = sample["entries"][:top_k]
                ranks = []
                for anchor in anchors:
                    rank = next((position for position, entry in enumerate(entries, 1)
                                 if anchor in (entry.get("preview") or "")), None)
                    ranks.append(rank)
                ranks_by_sample.append(ranks)
                aggregate[route]["latencies"].append(sample["latencyMs"])
            ranks = ranks_by_sample[0]
            relevant_ranks = sorted(set(rank for rank in ranks if rank is not None))
            hit = bool(relevant_ranks)
            recall = sum(rank is not None for rank in ranks) / len(anchors) if anchors else None
            mrr = 1 / relevant_ranks[0] if hit else 0.0
            ideal = sum(1 / math.log2(rank + 1) for rank in range(1, min(top_k, len(anchors)) + 1))
            dcg = sum(1 / math.log2(rank + 1) for rank in relevant_ranks)
            ndcg = dcg / ideal if ideal else None
            negative_clean = len(samples[0]["entries"]) == 0 if not anchors else None
            unstable = any(other != ranks for other in ranks_by_sample[1:])
            result[route] = {"relevantRanks": ranks, "hit": hit if anchors else None,
                             "negativeClean": negative_clean, "unstable": unstable,
                             "latenciesMs": [sample["latencyMs"] for sample in samples]}
            if anchors:
                aggregate[route]["hits"].append(int(hit))
                aggregate[route]["recall"].append(recall)
                aggregate[route]["mrr"].append(mrr)
                aggregate[route]["ndcg"].append(ndcg)
            else:
                aggregate[route]["negativeClean"].append(int(negative_clean))
            aggregate[route]["unstable"].append(unstable)
        per_case.append(result)
    metrics = {}
    for route, values in aggregate.items():
        positive_count = len(values["hits"])
        negative_count = len(values["negativeClean"])
        metrics[route] = {"positiveCount": positive_count, "negativeCount": negative_count,
                          "hitRateAtK": sum(values["hits"]) / positive_count if positive_count else None,
                          "recallAtK": sum(values["recall"]) / positive_count if positive_count else None,
                          "mrrAtK": sum(values["mrr"]) / positive_count if positive_count else None,
                          "ndcgAtK": sum(values["ndcg"]) / positive_count if positive_count else None,
                          "negativeCleanRate": sum(values["negativeClean"]) / negative_count if negative_count else None,
                          "p95Ms": percentile95(values["latencies"]), "latencySamples": len(values["latencies"]),
                          "unstableCases": sum(values["unstable"])}
    regressions = [case["id"] for case in per_case if case["positive"] and case["legacy"]["hit"]
                   and not case["ragflow"]["hit"]]
    critical_regressions = [case["id"] for case in per_case if case["critical"] and case["id"] in regressions]
    checks = capture.get("citationChecks") or []
    source_ids = {"kb:" + entry["chunkKey"] for run in capture["runs"] if run["route"] == "ragflow"
                  for entry in run["samples"][0]["entries"] if isinstance(entry.get("chunkKey"), str)}
    verified = {check["sourceId"] for check in checks if check.get("verified")}
    ingestion = capture.get("ingestion") or {}
    project_mappings = capture.get("projectMappings") or []
    old_p95 = metrics["legacy"]["p95Ms"]
    new_p95 = metrics["ragflow"]["p95Ms"]
    gates = {"atLeast25ProjectPositives": manifest.get("kind") == "project" and metrics["legacy"]["positiveCount"] >= 25,
             "goldLabelsReviewed": capture.get("conditions", {}).get("goldLabelsReviewed") is True,
             "positiveHitsBoth": all(metrics[route]["hitRateAtK"] is not None and metrics[route]["hitRateAtK"] > 0
                                      for route in ROUTES),
             "newMissesAtMostOne": len(regressions) <= 1,
             "criticalNoNewMisses": not critical_regressions,
             "negativeCasesPresent": metrics["legacy"]["negativeCount"] > 0,
             "negativeNoEvidence": all(case[route]["negativeClean"] for case in per_case if not case["positive"] for route in ROUTES),
             "citationVerified": bool(source_ids) and source_ids <= verified,
             "p95Within1_5x": old_p95 is not None and new_p95 is not None and new_p95 <= old_p95 * 1.5,
             "fixtureParsedBoth": all(ingestion.get(route, {}).get("status") == "DONE" for route in ROUTES),
             "projectMappingsDone": manifest.get("kind") == "project" and bool(project_mappings)
                 and all(mapping.get("status") == "DONE" for mapping in project_mappings),
             "stableRanking": all(not case[route]["unstable"] for case in per_case for route in ROUTES)}
    return {"schemaVersion": 1, "fixtureSha256": capture.get("fixtureSha256"),
            "conditions": capture.get("conditions"), "metrics": metrics, "cases": per_case,
            "newMisses": regressions, "criticalNewMisses": critical_regressions,
            "citationVerification": {"total": len(source_ids), "verified": len(source_ids & verified),
                                     "checks": checks, "attempted": capture.get("citationCheckAttempted", False)},
            "ingestion": ingestion or None, "projectMappings": project_mappings,
            "parseCompletionRate": sum(ingestion.get(route, {}).get("status") == "DONE" for route in ROUTES) / 2
                if ingestion else None,
            "gates": gates, "readyToSwitch": all(gates.values())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    collect_cmd = commands.add_parser("collect")
    collect_cmd.add_argument("--manifest", required=True)
    collect_cmd.add_argument("--conditions", required=True)
    collect_cmd.add_argument("--legacy-url", required=True)
    collect_cmd.add_argument("--ragflow-url", required=True)
    collect_cmd.add_argument("--ragflow-api-url", help="direct RAGFlow API base URL for citation backchecks")
    collect_cmd.add_argument("--output", required=True)
    collect_cmd.add_argument("--ingest-fixture", action="store_true")
    collect_cmd.add_argument("--repetitions", type=int, default=3)
    collect_cmd.add_argument("--warmup", type=int, default=1)
    score_cmd = commands.add_parser("score")
    score_cmd.add_argument("--capture", required=True)
    score_cmd.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command == "collect":
        if args.repetitions < 1 or args.warmup < 0:
            parser.error("repetitions must be positive and warmup nonnegative")
        collect(args)
    else:
        report = score(read_json(Path(args.capture)))
        write_json(Path(args.output), report)
        print(json.dumps({"readyToSwitch": report["readyToSwitch"], "gates": report["gates"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
