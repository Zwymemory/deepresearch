#!/usr/bin/env python3
"""Collect and score paired legacy/RAGFlow retrieval evidence (stdlib only)."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
import re
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROUTES = {"legacy": "rerankResult", "ragflow": "compressedContext"}
MAX_RESPONSE = 4 * 1024 * 1024
CITATION = re.compile(r"\[来源(\d+)]")
STAGE_TIMING_KEYS = ("queryRewrite", "registry", "upstreamApi", "evidenceNormalization", "responseAssembly", "total")


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize_text(value):
    """Apply the reviewed label contract without weakening case sensitivity."""
    if not isinstance(value, str):
        return ""
    return " ".join(unicodedata.normalize("NFKC", value).replace("`", "").split())


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


def case_kind(case):
    explicit = case.get("kind")
    if explicit:
        return explicit
    return "positive" if case.get("rankingAnchors") or case.get("relevantAnchors") else "zero-evidence"


def labels(case, name):
    value = case.get(name, [])
    if name == "rankingAnchors" and not value and "relevantAnchors" in case:
        return [{"id": f"legacy-anchor-{index}", "evidencePhrases": [phrase]}
                for index, phrase in enumerate(case.get("relevantAnchors", []), 1)]
    return value


def validate_labels(case_id, name, value, required=False):
    if not isinstance(value, list) or (required and not value):
        raise ValueError(f"{case_id}: {name} must be a nonempty list")
    ids = []
    for label in value:
        if not isinstance(label, dict):
            raise ValueError(f"{case_id}: each {name} label must be an object")
        label_id = label.get("id")
        phrases = label.get("evidencePhrases")
        if not isinstance(label_id, str) or not label_id.strip() or not isinstance(phrases, list) or not phrases:
            raise ValueError(f"{case_id}: each {name} label needs an ID and evidence phrases")
        normalized = [normalize_text(phrase) for phrase in phrases]
        if any(not phrase for phrase in normalized) or len(set(normalized)) != len(normalized):
            raise ValueError(f"{case_id}: {name} evidence phrases must be nonempty and unique")
        ids.append(label_id)
    if len(set(ids)) != len(ids):
        raise ValueError(f"{case_id}: {name} IDs must be unique")


def validate_manifest(manifest):
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("manifest requires cases")
    ids = [case.get("id") for case in cases]
    if any(not isinstance(case_id, str) or not case_id for case_id in ids) or len(set(ids)) != len(ids):
        raise ValueError("case IDs must be nonempty and unique")
    for case in cases:
        case_id = case["id"]
        if not isinstance(case.get("question"), str) or not case["question"].strip():
            raise ValueError(f"{case_id}: question required")
        kind = case_kind(case)
        if kind not in ("positive", "zero-evidence", "evidence-backed-safe-denial"):
            raise ValueError(f"{case_id}: unsupported kind {kind!r}")
        if "relevantAnchors" in case:
            legacy = case["relevantAnchors"]
            if not isinstance(legacy, list) or any(not isinstance(anchor, str) or not anchor for anchor in legacy) \
                    or len(set(legacy)) != len(legacy):
                raise ValueError(f"{case_id}: relevantAnchors must be unique strings")
        ranking = labels(case, "rankingAnchors")
        if kind == "positive":
            validate_labels(case_id, "rankingAnchors", ranking, required=True)
            validate_labels(case_id, "requiredFacts", labels(case, "requiredFacts"))
        elif ranking or labels(case, "requiredFacts") or case.get("critical", False):
            raise ValueError(f"{case_id}: negative cases cannot have ranking anchors or be critical")
        if kind == "evidence-backed-safe-denial":
            validate_labels(case_id, "denialEvidence", labels(case, "denialEvidence"), required=True)
            contract = case.get("answerContract")
            if not isinstance(contract, dict):
                raise ValueError(f"{case_id}: answerContract required")
            allowed = contract.get("mustContainAny")
            forbidden = contract.get("mustNotContain")
            if not isinstance(allowed, list) or not allowed or not isinstance(forbidden, list):
                raise ValueError(f"{case_id}: answer contract needs mustContainAny and mustNotContain")
            if any(not normalize_text(item) for item in allowed + forbidden):
                raise ValueError(f"{case_id}: answer contract phrases must be nonempty strings")
            if contract.get("citationRequired") is not True:
                raise ValueError(f"{case_id}: safe denial citations must be required")
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
            path += "/ragflow-sync"
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
    return response, round((time.perf_counter_ns() - start) / 1_000_000, 3)


def fetch_answer(base, token, case, top_k):
    start = time.perf_counter_ns()
    response = request_json(base + "/api/research/hybrid", token,
                            {"question": case["question"], "topK": top_k, "history": []})
    elapsed = round((time.perf_counter_ns() - start) / 1_000_000, 3)
    if not isinstance(response.get("answer"), str) or not isinstance(response.get("sources"), list):
        raise ValueError("answer endpoint must return answer and sources")
    return {"latencyMs": elapsed, "answer": response["answer"], "sources": response["sources"]}


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
    route_arg = getattr(args, "route", "both")
    if route_arg not in ("both", *ROUTES):
        raise ValueError(f"unsupported collection route {route_arg!r}")
    selected_routes = tuple(ROUTES) if route_arg == "both" else (route_arg,)
    route_urls = {"legacy": getattr(args, "legacy_url", None),
                  "ragflow": getattr(args, "ragflow_url", None)}
    missing_urls = [route for route in selected_routes if not route_urls[route]]
    if missing_urls:
        raise ValueError("missing URL for selected route(s): " + ", ".join(missing_urls))
    bases = {route: base_url(route_urls[route]) for route in selected_routes}
    tokens = {"legacy": os.getenv("EVAL_LEGACY_TOKEN"), "ragflow": os.getenv("EVAL_RAGFLOW_TOKEN")}
    missing_ingestion_tokens = [route for route in selected_routes if not tokens[route]]
    if args.ingest_fixture and missing_ingestion_tokens:
        names = ", ".join("EVAL_" + route.upper() + "_TOKEN" for route in missing_ingestion_tokens)
        raise ValueError(f"fixture ingestion requires {names}")
    if manifest.get("kind") == "project" and "ragflow" in selected_routes and not tokens["ragflow"]:
        raise ValueError("EVAL_RAGFLOW_TOKEN is required to verify project document mappings")
    ingestion = {}
    if args.ingest_fixture:
        for route in selected_routes:
            upload = upload_fixture(bases[route], tokens[route], fixture)
            doc_id = upload.get("docId")
            if not doc_id:
                raise ValueError(f"{route} upload did not return docId")
            ingestion[route] = wait_for_ingestion(bases[route], tokens[route], doc_id, route)
    project_mappings = []
    if manifest.get("kind") == "project" and "ragflow" in selected_routes:
        for doc_id in project_doc_ids:
            project_mappings.append(wait_for_ingestion(bases["ragflow"], tokens["ragflow"], doc_id, "ragflow"))
    runs = []
    top_k = manifest["topK"]
    for case_index, case in enumerate(manifest["cases"]):
        order = list(selected_routes)
        if len(order) > 1 and case_index % 2:
            order.reverse()
        for route in order:
            for _ in range(args.warmup):
                try:
                    fetch_entries(bases[route], tokens[route], case, top_k)
                except (OSError, ValueError, RuntimeError) as error:
                    raise RuntimeError(f"{case['id']} {route} warmup failed: {error}") from error
            samples = []
            for repetition in range(1, args.repetitions + 1):
                try:
                    response, elapsed = fetch_entries(bases[route], tokens[route], case, top_k)
                except (OSError, ValueError, RuntimeError) as error:
                    raise RuntimeError(
                        f"{case['id']} {route} repetition {repetition} failed: {error}") from error
                if response.get("provider") != route:
                    raise ValueError(f"{route} endpoint reported provider={response.get('provider')!r}")
                field = ROUTES[route]
                entries = response.get(field)
                if not isinstance(entries, list):
                    raise ValueError(f"{route} response missing {field}")
                scores = response.get("similarityScores")
                if not isinstance(scores, dict):
                    raise ValueError(f"{route} response missing similarityScores")
                sample = {"latencyMs": elapsed, "entries": [
                    {**{key: entry.get(key) for key in ("chunkKey", "docId", "chunkId", "title", "preview", "route")},
                     "similarityScore": scores.get(entry.get("chunkKey"))}
                    for entry in entries[:top_k]]}
                stage_timings = response.get("stageTimingMs")
                if isinstance(stage_timings, dict):
                    sample["stageTimingMs"] = {key: stage_timings[key] for key in STAGE_TIMING_KEYS
                                               if isinstance(stage_timings.get(key), (int, float))
                                               and not isinstance(stage_timings.get(key), bool)}
                if case_kind(case) == "evidence-backed-safe-denial":
                    sample["answerSample"] = fetch_answer(bases[route], tokens[route], case, top_k)
                samples.append(sample)
            runs.append({"caseId": case["id"], "route": route, "samples": samples})
    verification = []
    ragflow_api_url = getattr(args, "ragflow_api_url", None)
    citation_attempted = bool("ragflow" in selected_routes and ragflow_api_url
                              and os.getenv("RAGFLOW_API_KEY"))
    if citation_attempted:
        keys = {entry["chunkKey"] for run in runs if run["route"] == "ragflow"
                for sample in run["samples"] for entry in sample["entries"]
                if isinstance(entry.get("chunkKey"), str)}
        verification = [citation_check(base_url(ragflow_api_url), os.environ["RAGFLOW_API_KEY"], key)
                        for key in sorted(keys)]
    capture = {"schemaVersion": 2, "collectedRoutes": list(selected_routes), "manifest": manifest,
               "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
               "conditions": conditions, "ingestion": ingestion or None,
               "projectMappings": project_mappings, "runs": runs,
               "citationChecks": verification,
               "citationCheckAttempted": citation_attempted}
    write_json(Path(args.output), capture)
    return capture


def run_index(capture, expected_routes):
    manifest = capture.get("manifest")
    if not isinstance(manifest, dict):
        raise ValueError("capture manifest is missing")
    validate_manifest(manifest)
    runs = capture.get("runs")
    if not isinstance(runs, list):
        raise ValueError("capture runs must be a list")
    expected = {(case["id"], route) for case in manifest["cases"] for route in expected_routes}
    indexed = {}
    for run in runs:
        if not isinstance(run, dict):
            raise ValueError("every capture run must be an object")
        if not isinstance(run.get("caseId"), str) or not isinstance(run.get("route"), str):
            raise ValueError("every capture run needs string caseId and route")
        identity = (run.get("caseId"), run.get("route"))
        if identity in indexed:
            raise ValueError(f"duplicate run for case={identity[0]!r} route={identity[1]!r}")
        indexed[identity] = run
    if set(indexed) != expected:
        missing = sorted(expected - set(indexed), key=repr)
        extra = sorted(set(indexed) - expected, key=repr)
        raise ValueError(f"capture run set mismatch; missing={missing!r}, extra={extra!r}")
    return indexed


def validate_partial_capture(capture, route):
    collected = capture.get("collectedRoutes")
    if collected != [route]:
        raise ValueError(f"{route} partial must declare collectedRoutes=[{route!r}]")
    if capture.get("schemaVersion") != 2:
        raise ValueError(f"{route} partial must use capture schemaVersion 2")
    fixture_sha = capture.get("fixtureSha256")
    if not isinstance(fixture_sha, str) or not fixture_sha:
        raise ValueError(f"{route} partial must record fixtureSha256")
    if not isinstance(capture.get("conditions"), dict) or not capture["conditions"]:
        raise ValueError(f"{route} partial must record conditions")
    ingestion = capture.get("ingestion") or {}
    if not isinstance(ingestion, dict) or any(key != route for key in ingestion):
        raise ValueError(f"{route} partial ingestion may only contain the selected route")
    if any(not isinstance(status, dict) for status in ingestion.values()):
        raise ValueError(f"{route} partial ingestion records must be objects")
    if not isinstance(capture.get("citationCheckAttempted"), bool):
        raise ValueError(f"{route} partial must record citationCheckAttempted")
    indexed = run_index(capture, (route,))
    cases = {case["id"]: case for case in capture["manifest"]["cases"]}
    for (case_id, _), run in indexed.items():
        samples = run.get("samples")
        if not isinstance(samples, list) or not samples:
            raise ValueError(f"{case_id} {route} run needs measured samples")
        for sample in samples:
            score_sample(cases[case_id], sample, capture["manifest"]["topK"])
    return indexed


def merge_keyed_records(name, key, captures):
    merged = []
    by_key = {}
    for capture in captures:
        records = capture.get(name) or []
        if not isinstance(records, list):
            raise ValueError(f"{name} must be a list")
        for record in records:
            if not isinstance(record, dict) or not isinstance(record.get(key), str) or not record[key]:
                raise ValueError(f"every {name} record needs a nonempty {key}")
            identity = record[key]
            if identity in by_key:
                if by_key[identity] != record:
                    raise ValueError(f"conflicting {name} records for {identity!r}")
                continue
            cloned = copy.deepcopy(record)
            by_key[identity] = cloned
            merged.append(cloned)
    return merged


def merge_captures(legacy_capture, ragflow_capture):
    captures = (legacy_capture, ragflow_capture)
    indexes = {
        "legacy": validate_partial_capture(legacy_capture, "legacy"),
        "ragflow": validate_partial_capture(ragflow_capture, "ragflow"),
    }
    for field in ("manifest", "fixtureSha256", "conditions"):
        if legacy_capture.get(field) != ragflow_capture.get(field):
            raise ValueError(f"partial captures have different {field}")
    sample_counts = {len(run["samples"]) for index in indexes.values() for run in index.values()}
    if len(sample_counts) != 1:
        raise ValueError("partial captures must use the same repetition count for every case and route")

    ingestion = {}
    for capture in captures:
        for route, status in (capture.get("ingestion") or {}).items():
            if route in ingestion and ingestion[route] != status:
                raise ValueError(f"conflicting ingestion records for {route!r}")
            ingestion[route] = copy.deepcopy(status)

    manifest = legacy_capture["manifest"]
    runs = [copy.deepcopy(indexes[route][(case["id"], route)])
            for case in manifest["cases"] for route in ROUTES]
    merged = {
        "schemaVersion": 2,
        "collectedRoutes": list(ROUTES),
        "manifest": copy.deepcopy(manifest),
        "fixtureSha256": legacy_capture["fixtureSha256"],
        "conditions": copy.deepcopy(legacy_capture["conditions"]),
        "ingestion": ingestion or None,
        "projectMappings": merge_keyed_records("projectMappings", "docId", captures),
        "runs": runs,
        "citationChecks": merge_keyed_records("citationChecks", "sourceId", captures),
        "citationCheckAttempted": any(capture.get("citationCheckAttempted") is True
                                      for capture in captures),
    }
    run_index(merged, tuple(ROUTES))
    return merged


def percentile95(values):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[math.ceil(0.95 * len(ordered)) - 1]


def label_ranks(entries, logical_labels, top_k):
    normalized = [normalize_text(entry.get("preview")) for entry in entries[:top_k]]
    return {label["id"]: next((position for position, preview in enumerate(normalized, 1)
                               if any(normalize_text(phrase) in preview
                                      for phrase in label["evidencePhrases"])), None)
            for label in logical_labels}


def answer_contract_score(answer_sample, contract):
    if not isinstance(answer_sample, dict):
        return {"captured": False, "allowedPhrase": False, "forbiddenPhrase": False,
                "citationValid": False, "passed": False, "failureReasons": ["answer sample missing"]}
    answer = normalize_text(answer_sample.get("answer"))
    sources = answer_sample.get("sources")
    sources = sources if isinstance(sources, list) else []
    allowed = any(normalize_text(phrase) in answer for phrase in contract["mustContainAny"])
    forbidden = any(normalize_text(phrase) in answer for phrase in contract["mustNotContain"])
    indices = {source.get("index") for source in sources if isinstance(source, dict)
               and isinstance(source.get("index"), int)}
    markers = [int(value) for value in CITATION.findall(answer_sample.get("answer") or "")]
    citation_valid = bool(markers) and bool(indices) and all(marker in indices for marker in markers)
    reasons = []
    if not allowed:
        reasons.append("allowed denial phrase missing")
    if forbidden:
        reasons.append("forbidden phrase present")
    if contract.get("citationRequired") and not citation_valid:
        reasons.append("valid citation missing")
    return {"captured": True, "allowedPhrase": allowed, "forbiddenPhrase": forbidden,
            "citationValid": citation_valid, "passed": not reasons, "failureReasons": reasons}


def score_sample(case, sample, top_k):
    kind = case_kind(case)
    entries = sample.get("entries")
    if not isinstance(entries, list) or not isinstance(sample.get("latencyMs"), (int, float)):
        raise ValueError("every sample needs entries and numeric latencyMs")
    result = {"latencyMs": sample["latencyMs"]}
    if isinstance(sample.get("stageTimingMs"), dict):
        result["stageTimingMs"] = {key: sample["stageTimingMs"][key] for key in STAGE_TIMING_KEYS
                                   if isinstance(sample["stageTimingMs"].get(key), (int, float))
                                   and not isinstance(sample["stageTimingMs"].get(key), bool)}
    if kind == "positive":
        anchor_ranks = label_ranks(entries, labels(case, "rankingAnchors"), top_k)
        fact_ranks = label_ranks(entries, labels(case, "requiredFacts"), top_k)
        relevant_ranks = sorted(set(rank for rank in anchor_ranks.values() if rank is not None))
        ideal = sum(1 / math.log2(rank + 1)
                    for rank in range(1, min(top_k, len(anchor_ranks)) + 1))
        dcg = sum(1 / math.log2(rank + 1) for rank in relevant_ranks)
        result.update({"rankingAnchorRanks": anchor_ranks,
                       "rankingHit": any(rank is not None for rank in anchor_ranks.values()),
                       "rankingAnchorsCovered": all(rank is not None for rank in anchor_ranks.values()),
                       "rankingAnchorCoverage": sum(rank is not None for rank in anchor_ranks.values()) / len(anchor_ranks),
                       "recallAtK": sum(rank is not None for rank in anchor_ranks.values()) / len(anchor_ranks),
                       "mrrAtK": 1 / relevant_ranks[0] if relevant_ranks else 0.0,
                       "ndcgAtK": dcg / ideal if ideal else None,
                       "requiredFactRanks": fact_ranks,
                       "answerableAtK": all(rank is not None for rank in fact_ranks.values()) if fact_ranks else None})
    elif kind == "zero-evidence":
        result.update({"negativeClean": len(entries[:top_k]) == 0,
                       "negativeContractPassed": len(entries[:top_k]) == 0})
    else:
        evidence_ranks = label_ranks(entries, labels(case, "denialEvidence"), top_k)
        evidence_covered = all(rank is not None for rank in evidence_ranks.values())
        answer = answer_contract_score(sample.get("answerSample"), case["answerContract"])
        result.update({"denialEvidenceRanks": evidence_ranks, "denialEvidenceCovered": evidence_covered,
                       "answerContract": answer, "negativeContractPassed": evidence_covered and answer["passed"]})
    return result


def reviewed_labels_match(manifest, conditions):
    if manifest.get("kind") != "project":
        return conditions.get("goldLabelsReviewed") is True
    review = manifest.get("review") or {}
    expected = {item.get("path"): item.get("sha256") for item in review.get("canonicalSources", [])
                if isinstance(item, dict)}
    corpus = conditions.get("corpus") or {}
    actual = {item.get("path"): item.get("sha256") for item in corpus.get("files", [])
              if isinstance(item, dict)}
    return review.get("reviewed") is True and bool(expected) and expected == actual


def timing_aggregate(samples_by_stage):
    result = {}
    for stage in STAGE_TIMING_KEYS:
        values = samples_by_stage.get(stage, [])
        if values:
            result[stage] = {"sampleCount": len(values), "meanMs": sum(values) / len(values),
                             "p95Ms": percentile95(values)}
    return result


def score(capture, manifest=None):
    manifest = manifest or capture["manifest"]
    validate_manifest(manifest)
    top_k = manifest["topK"]
    collected = capture.get("collectedRoutes")
    if collected is not None and collected != list(ROUTES):
        raise ValueError("partial capture cannot be scored directly; merge legacy and ragflow captures first")
    scoring_capture = capture if manifest is capture.get("manifest") else {**capture, "manifest": manifest}
    indexed = run_index(scoring_capture, tuple(ROUTES))
    per_case = []
    aggregate = {route: {"firstHits": [], "everyHits": [], "firstCoverage": [], "everyCoverage": [],
                         "firstRecall": [], "firstMrr": [], "firstNdcg": [],
                         "firstAnswerable": [], "everyAnswerable": [], "latencies": [],
                         "zeroFirst": [], "zeroEvery": [], "safeFirst": [], "safeEvery": [],
                         "unstable": [], "stages": {}} for route in ROUTES}
    for case in manifest["cases"]:
        kind = case_kind(case)
        result = {"id": case["id"], "kind": kind, "critical": bool(case.get("critical")),
                  "positive": kind == "positive"}
        for route in ROUTES:
            samples = indexed[(case["id"], route)].get("samples")
            if not samples:
                raise ValueError("every run needs measured samples")
            scored = [score_sample(case, sample, top_k) for sample in samples]
            for sample in samples:
                aggregate[route]["latencies"].append(sample["latencyMs"])
                for stage in STAGE_TIMING_KEYS:
                    value = (sample.get("stageTimingMs") or {}).get(stage)
                    if isinstance(value, (int, float)) and not isinstance(value, bool):
                        aggregate[route]["stages"].setdefault(stage, []).append(value)
            first = scored[0]
            if kind == "positive":
                first_ranks = list(first["rankingAnchorRanks"].values())
                first_recall = first["rankingAnchorCoverage"]
                first_mrr = first["mrrAtK"]
                first_ndcg = first["ndcgAtK"]
                every_hit = all(item["rankingHit"] for item in scored)
                every_coverage = all(item["rankingAnchorsCovered"] for item in scored)
                required = bool(labels(case, "requiredFacts"))
                every_answerable = all(item["answerableAtK"] is True for item in scored) if required else None
                rank_signatures = [tuple(item["rankingAnchorRanks"].values()) for item in scored]
                coverage_signatures = [(tuple(item["rankingAnchorRanks"].values()),
                                        tuple(item["requiredFactRanks"].values())) for item in scored]
                aggregate[route]["firstHits"].append(int(first["rankingHit"]))
                aggregate[route]["everyHits"].append(int(every_hit))
                aggregate[route]["firstCoverage"].append(first_recall)
                aggregate[route]["everyCoverage"].append(int(every_coverage))
                aggregate[route]["firstRecall"].append(first_recall)
                aggregate[route]["firstMrr"].append(first_mrr)
                aggregate[route]["firstNdcg"].append(first_ndcg)
                if required:
                    aggregate[route]["firstAnswerable"].append(int(first["answerableAtK"]))
                    aggregate[route]["everyAnswerable"].append(int(every_answerable))
                unstable = any(signature != rank_signatures[0] for signature in rank_signatures[1:])
                coverage_unstable = any(signature != coverage_signatures[0] for signature in coverage_signatures[1:])
                result[route] = {"relevantRanks": first_ranks, "hit": first["rankingHit"],
                                 "requiredFactRanks": first["requiredFactRanks"],
                                 "answerableAtK": first["answerableAtK"], "negativeClean": None,
                                 "firstSample": first, "samples": scored,
                                 "everySample": {"rankingHit": every_hit,
                                                 "rankingAnchorsCovered": every_coverage,
                                                 "requiredFactsCovered": every_answerable},
                                 "unstable": unstable, "coverageUnstable": coverage_unstable,
                                 "latenciesMs": [item["latencyMs"] for item in scored]}
            else:
                contract_values = [item["negativeContractPassed"] for item in scored]
                every_contract = all(contract_values)
                if kind == "zero-evidence":
                    aggregate[route]["zeroFirst"].append(int(first["negativeClean"]))
                    aggregate[route]["zeroEvery"].append(int(every_contract))
                    negative_clean = first["negativeClean"]
                else:
                    aggregate[route]["safeFirst"].append(int(first["negativeContractPassed"]))
                    aggregate[route]["safeEvery"].append(int(every_contract))
                    negative_clean = None
                signatures = [json.dumps({key: value for key, value in item.items()
                                          if key not in ("latencyMs", "stageTimingMs")},
                                         sort_keys=True, ensure_ascii=False) for item in scored]
                unstable = any(signature != signatures[0] for signature in signatures[1:])
                result[route] = {"relevantRanks": [], "hit": None, "requiredFactRanks": {},
                                 "answerableAtK": None, "negativeClean": negative_clean,
                                 "firstSample": first, "samples": scored,
                                 "everySample": {"negativeContractPassed": every_contract},
                                 "unstable": unstable, "coverageUnstable": unstable,
                                 "latenciesMs": [item["latencyMs"] for item in scored]}
            aggregate[route]["unstable"].append(result[route]["unstable"])
        per_case.append(result)
    metrics = {}
    for route, values in aggregate.items():
        positives = len(values["firstHits"])
        answerable = len(values["firstAnswerable"])
        zero_count = len(values["zeroFirst"])
        safe_count = len(values["safeFirst"])
        metrics[route] = {
            "positiveCount": positives, "zeroEvidenceNegativeCount": zero_count,
            "safeDenialCount": safe_count, "negativeCount": zero_count + safe_count,
            "answerableCaseCount": answerable,
            "hitRateAtK": sum(values["firstHits"]) / positives if positives else None,
            "everySampleHitRateAtK": sum(values["everyHits"]) / positives if positives else None,
            "rankingAnchorCoverageAtK": sum(values["firstCoverage"]) / positives if positives else None,
            "everySampleRankingAnchorCoverageAtK": sum(values["everyCoverage"]) / positives if positives else None,
            "recallAtK": sum(values["firstRecall"]) / positives if positives else None,
            "mrrAtK": sum(values["firstMrr"]) / positives if positives else None,
            "ndcgAtK": sum(values["firstNdcg"]) / positives if positives else None,
            "answerableAtK": sum(values["firstAnswerable"]) / answerable if answerable else None,
            "everySampleAnswerableAtK": sum(values["everyAnswerable"]) / answerable if answerable else None,
            "negativeCleanRate": sum(values["zeroFirst"]) / zero_count if zero_count else None,
            "everySampleNegativeCleanRate": sum(values["zeroEvery"]) / zero_count if zero_count else None,
            "safeDenialPassRate": sum(values["safeFirst"]) / safe_count if safe_count else None,
            "everySampleSafeDenialPassRate": sum(values["safeEvery"]) / safe_count if safe_count else None,
            "p95Ms": percentile95(values["latencies"]), "latencySamples": len(values["latencies"]),
            "stageTimingMs": timing_aggregate(values["stages"]),
            "unstableCases": sum(values["unstable"])}
    regressions = [case["id"] for case in per_case if case["positive"] and case["legacy"]["hit"]
                   and not case["ragflow"]["hit"]]
    critical_regressions = [case["id"] for case in per_case if case["critical"] and case["id"] in regressions]
    answerability_regressions = [case["id"] for case in per_case
                                 if case["legacy"]["answerableAtK"] is True
                                 and case["ragflow"]["answerableAtK"] is False]
    critical_answerability_regressions = [case["id"] for case in per_case
                                          if case["critical"] and case["id"] in answerability_regressions]
    checks = capture.get("citationChecks") or []
    source_ids = {"kb:" + entry["chunkKey"] for run in capture["runs"] if run["route"] == "ragflow"
                  for sample in (run["samples"] if capture.get("schemaVersion", 1) >= 2 else run["samples"][:1])
                  for entry in sample.get("entries", [])
                  if isinstance(entry.get("chunkKey"), str)}
    verified = {check["sourceId"] for check in checks if check.get("verified")}
    ingestion = capture.get("ingestion") or {}
    project_mappings = capture.get("projectMappings") or []
    old_p95, new_p95 = metrics["legacy"]["p95Ms"], metrics["ragflow"]["p95Ms"]
    negatives = [case for case in per_case if not case["positive"]]
    safe_denials = [case for case in per_case if case["kind"] == "evidence-backed-safe-denial"]
    zero_evidence = [case for case in per_case if case["kind"] == "zero-evidence"]
    annotated = [case for case in per_case if case["positive"]
                 and any(route_case["answerableAtK"] is not None for route_case in
                         (case["legacy"], case["ragflow"]))]
    gates = {
        "atLeast25ProjectPositives": manifest.get("kind") == "project" and metrics["legacy"]["positiveCount"] >= 25,
        "goldLabelsReviewed": reviewed_labels_match(manifest, capture.get("conditions", {})),
        "positiveHitsBoth": all(metrics[route]["hitRateAtK"] is not None and metrics[route]["hitRateAtK"] > 0
                                for route in ROUTES),
        "newMissesAtMostOne": len(regressions) <= 1,
        "criticalNoNewMisses": not critical_regressions,
        "ragflowRequiredFactsEverySample": bool(annotated) and all(
            case["ragflow"]["everySample"]["requiredFactsCovered"] is True for case in annotated),
        "negativeCasesPresent": bool(negatives),
        "negativeContractsSatisfied": bool(negatives) and all(
            case[route]["everySample"]["negativeContractPassed"] for case in negatives for route in ROUTES),
        "zeroEvidenceNegativesSatisfied": not zero_evidence or all(
            case[route]["everySample"]["negativeContractPassed"] for case in zero_evidence for route in ROUTES),
        "safeDenialsSatisfied": not safe_denials or all(
            case[route]["everySample"]["negativeContractPassed"] for case in safe_denials for route in ROUTES),
        "citationVerified": bool(source_ids) and source_ids <= verified,
        "p95Within1_5x": old_p95 is not None and new_p95 is not None and new_p95 <= old_p95 * 1.5,
        "fixtureParsedBoth": all(ingestion.get(route, {}).get("status") == "DONE" for route in ROUTES),
        "projectMappingsDone": manifest.get("kind") == "project" and bool(project_mappings)
            and all(mapping.get("status") == "DONE" for mapping in project_mappings),
        "stableRanking": all(not case[route]["unstable"] for case in per_case for route in ROUTES)}
    return {"schemaVersion": 2, "fixtureSha256": capture.get("fixtureSha256"),
            "contractReview": manifest.get("review"), "conditions": capture.get("conditions"),
            "metrics": metrics, "cases": per_case,
            "newMisses": regressions, "criticalNewMisses": critical_regressions,
            "newAnswerabilityMisses": answerability_regressions,
            "criticalNewAnswerabilityMisses": critical_answerability_regressions,
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
    collect_cmd.add_argument("--route", choices=("both", *ROUTES), default="both")
    collect_cmd.add_argument("--legacy-url")
    collect_cmd.add_argument("--ragflow-url")
    collect_cmd.add_argument("--ragflow-api-url", help="direct RAGFlow API base URL for citation backchecks")
    collect_cmd.add_argument("--output", required=True)
    collect_cmd.add_argument("--ingest-fixture", action="store_true")
    collect_cmd.add_argument("--repetitions", type=int, default=3)
    collect_cmd.add_argument("--warmup", type=int, default=1)
    merge_cmd = commands.add_parser("merge")
    merge_cmd.add_argument("--legacy-capture", required=True)
    merge_cmd.add_argument("--ragflow-capture", required=True)
    merge_cmd.add_argument("--output", required=True)
    score_cmd = commands.add_parser("score")
    score_cmd.add_argument("--capture", required=True)
    score_cmd.add_argument("--manifest", help="reviewed contract to apply to an older compatible capture")
    score_cmd.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command == "collect":
        if args.repetitions < 1 or args.warmup < 0:
            parser.error("repetitions must be positive and warmup nonnegative")
        collect(args)
    elif args.command == "merge":
        merged = merge_captures(read_json(Path(args.legacy_capture)), read_json(Path(args.ragflow_capture)))
        write_json(Path(args.output), merged)
        print(json.dumps({"collectedRoutes": merged["collectedRoutes"],
                          "caseCount": len(merged["manifest"]["cases"])}, ensure_ascii=False))
    else:
        manifest = read_json(Path(args.manifest)) if args.manifest else None
        report = score(read_json(Path(args.capture)), manifest)
        write_json(Path(args.output), report)
        print(json.dumps({"readyToSwitch": report["readyToSwitch"], "gates": report["gates"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
