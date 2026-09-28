#!/usr/bin/env python3
"""Audit captured Dify node outputs without publishing provider reasoning or secrets.

The private JSONL input is a minimal database export joined to Java run IDs.
No requests to a model or tool are made. Only stdlib and repository modules are used.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

from build_workflow import PUBLIC_BOUNDARY_POLICY, STRICT_LLM_JSON

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ragflow"))
from showcase_eval import percentile95, redact_text  # noqa: E402


def audit(capture, trace, contract):
    namespace = {}
    exec(STRICT_LLM_JSON, namespace)
    exec(PUBLIC_BOUNDARY_POLICY, namespace)
    parse = namespace["parse_llm_json"]
    exact = namespace["exact_fields"]
    roots = {"planner": ("tasks",), "reviewer": ("verdict", "followups"),
             "synthesizer_direct": ("status", "answer", "citations"),
             "synthesizer": ("status", "answer", "citations")}
    if contract == "v6":
        for node in roots:
            if node != "planner":
                roots[node] += ("answer_kind", "boundary_support")
    by_run = {row["javaRunId"]: row for row in trace}
    rows = []
    cases = {case["id"]: case for case in capture["suite"]["cases"]}
    for sample in capture["samples"]:
        answer = sample["answerRun"]
        raw = by_run[answer["runId"]]
        if raw["workflowId"] != capture["conditions"]["publishedWorkflowId"]:
            raise ValueError("published workflow mismatch")
        nodes = []
        for item in raw.get("llmNodes") or []:
            text = item.get("text") or ""
            body = text.strip()
            reasoning = body.startswith("<think>")
            if reasoning:
                # The original text is strictly parsed below; this only omits reasoning.
                body = body.split("</think>", 1)[-1].strip() if "</think>" in body else ""
            parsed, error, schema = None, None, False
            try:
                parsed = parse(text)
                exact(parsed, roots[item["id"]])
                schema = True
            except (ValueError, TypeError, KeyError) as exc:
                error = type(exc).__name__
            usage = item.get("usage") or {}
            public_body = redact_text(body)
            nodes.append({"id": item["id"], "status": item["status"],
                          "finishReason": item.get("finishReason"),
                          "reasoningWrapperPresent": reasoning,
                          "rawResponseSha256": hashlib.sha256(text.encode()).hexdigest(),
                          "responseBody": public_body, "responseBodyRedacted": public_body != body,
                          "jsonObjectValid": parsed is not None, "exactRootSchemaValid": schema,
                          "parseOrSchemaError": error,
                          "usage": {key: usage[key] for key in
                                    ("prompt_tokens", "completion_tokens", "total_tokens", "total_price", "currency")
                                    if key in usage}})
        model_calls = len(nodes)
        tool_calls = raw["toolCalls"]
        if model_calls > 3 or tool_calls > 4:
            raise ValueError("observed call budget exceeded")
        queries = raw.get("toolQueries") or []
        expected = namespace["public_boundary_query"](cases[sample["caseId"]]["question"])
        boundary_only = all(query["tool"] == "kb_search" and query["input"] == expected for query in queries) if expected and queries else None
        if boundary_only is False:
            raise ValueError("sensitive-value request escaped fixed public-boundary queries")
        rows.append({"caseId": sample["caseId"], "repetition": sample["repetition"],
                     "javaRunId": answer["runId"], "difyRunId": raw["runId"],
                     "javaStatus": answer["status"], "difyStatus": raw["status"],
                     "difyTotalTokens": raw["totalTokens"], "modelCalls": model_calls,
                     "toolCalls": tool_calls, "llmNodes": nodes,
                     "toolQueries": [{"tool": query["tool"], "input": redact_text(query["input"])} for query in queries],
                     "privateBoundaryQueriesVerified": boundary_only,
                     "validators": raw.get("validators") or [],
                     "formatPassed": all(node["exactRootSchemaValid"] for node in nodes)})
    nodes = [node for row in rows for node in row["llmNodes"]]
    token_values = [row["difyTotalTokens"] for row in rows if row["difyTotalTokens"] is not None]
    return {"schemaVersion": 1, "contract": contract,
            "codeSha": capture["conditions"]["codeSha"],
            "dslSha256": capture["conditions"]["dslSha256"],
            "publishedWorkflowId": capture["conditions"]["publishedWorkflowId"],
            "summary": {"sampleCount": len(rows), "llmNodeCount": len(nodes),
                        "jsonObjectFailures": sum(not node["jsonObjectValid"] for node in nodes),
                        "exactRootSchemaFailures": sum(not node["exactRootSchemaValid"] for node in nodes),
                        "finishReasonLengthCount": sum(node["finishReason"] == "length" for node in nodes),
                        "samplesWithFormatFailure": sum(not row["formatPassed"] for row in rows),
                        "validatorFailedCount": sum(node["status"] == "FAILED" for row in rows for node in row["validators"]),
                        "modelCalls": sum(row["modelCalls"] for row in rows),
                        "toolCalls": sum(row["toolCalls"] for row in rows),
                        "maxModelCallsPerRun": max(row["modelCalls"] for row in rows),
                        "maxToolCallsPerRun": max(row["toolCalls"] for row in rows),
                        "tokenSampleCount": len(token_values), "totalTokens": sum(token_values),
                        "meanTotalTokens": sum(token_values) / len(token_values) if token_values else None,
                        "p95TotalTokens": percentile95(token_values) if token_values else None},
            "interpretation": "Matched by Java run ID to Dify database node records. JSON and exact root fields are independent of factual correctness. Existing Code nodes validate remaining types, scopes and citation mapping. Provider thinking content is omitted; response bodies are redacted. Dify plugin price fields may be zero/unconfigured and are not a billing measurement. Debug retrieval probes are excluded from workflow tool counts. No format repair or extra model call is made.",
            "samples": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", required=True)
    parser.add_argument("--native-trace", required=True)
    parser.add_argument("--contract", choices=("v5", "v6"), required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    capture = json.loads(Path(args.capture).read_text())
    trace = [json.loads(line) for line in Path(args.native_trace).read_text().splitlines() if line.strip()]
    report = audit(capture, trace, args.contract)
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report["summary"], ensure_ascii=False))


if __name__ == "__main__":
    main()
