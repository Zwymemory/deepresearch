#!/usr/bin/env python3
"""Offline contract checks for the generated Dify graph and its code nodes."""

from __future__ import annotations

import json
import re
from pathlib import Path


HERE = Path(__file__).resolve().parent
DSL = json.loads((HERE / "deepresearch-evidence-v1.yml").read_text())
GRAPH = DSL["workflow"]["graph"]
NODES = {node["id"]: node for node in GRAPH["nodes"]}


def call(node_id: str, **inputs):
    namespace = {}
    exec(NODES[node_id]["data"]["code"], namespace)
    return namespace["main"](**inputs)


def verify_graph() -> None:
    assert DSL["version"] == "0.7.0"
    assert DSL["app"]["mode"] == "workflow"
    assert len(NODES) == len(GRAPH["nodes"])
    for edge in GRAPH["edges"]:
        assert edge["source"] in NODES and edge["target"] in NODES
        assert edge["data"]["sourceType"] == NODES[edge["source"]]["data"]["type"]
        assert edge["data"]["targetType"] == NODES[edge["target"]]["data"]["type"]
    env_names = {entry["name"] for entry in DSL["workflow"]["environment_variables"]}
    for node in NODES.values():
        for ref in re.findall(r"\{\{#([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)#\}\}", json.dumps(node["data"])):
            assert ref[1] in env_names if ref[0] == "env" else ref[0] in NODES
    for node_id in ("workers", "revision_workers"):
        node = NODES[node_id]["data"]
        assert node["type"] == "iteration" and node["is_parallel"] is True and node["parallel_nums"] == 2
        http = NODES[node_id + "_http"]["data"]
        assert http["retry_config"]["enabled"] is False
        assert "DIFY_TOOL_SERVICE_TOKEN#}}" in http["headers"]
        assert http["url"].endswith("/internal/dify/tools/{{#" + node_id + "_parse.tool#}}")
        assert http["body"] == {"type": "json", "data": [{"key": "", "type": "text", "value": "{{#" + node_id + "_parse.body#}}"}]}
    assert all(node["data"].get("type") != "tool" for node in NODES.values())
    assert all("ragflow" not in str(node["data"].get("url", "")) for node in NODES.values())
    llms = [node for node in NODES.values() if node["data"]["type"] == "llm"]
    assert len(llms) == 4
    assert all(node["data"]["model"]["provider"] == "langgenius/deepseek/deepseek" for node in llms)
    assert all(node["data"]["model"]["name"] == "deepseek-v4-flash" for node in llms)
    assert any(edge["source"] == "revision_gate" and edge["sourceHandle"] == "false" and edge["target"] == "synthesizer_direct" for edge in GRAPH["edges"])
    assert any(edge["source"] == "revision_gate" and edge["sourceHandle"] == "true" and edge["target"] == "revision_workers" for edge in GRAPH["edges"])


def verify_contract() -> None:
    run_id = "3d6edbc1-219c-4c72-a814-0b2652b2582c"
    plan = call("plan", plan_text=json.dumps({"tasks": [{"tool": "kb_search", "input": "policy"}, {"tool": "calculator", "input": "2+2"}]}), question="policy?", java_run_id=run_id, allowed_tools="kb_search,calculator")
    assert plan["status"] == "READY" and len(plan["requests"]) == 2
    thinking_plan = '<think><!--dify-deepseek-reasoning-->read-only search</think>{"tasks":[{"tool":"kb_search","input":"policy"}]}'
    assert call("plan", plan_text=thinking_plan, question="policy?", java_run_id=run_id, allowed_tools="kb_search")["status"] == "READY"
    for malformed in (
        '<think>unclosed{"tasks":[{"tool":"kb_search","input":"policy"}]}',
        '<think><think>nested</think>{"tasks":[{"tool":"kb_search","input":"policy"}]}',
        '<think>analysis</think>{"tasks":[{"tool":"kb_search","input":"policy"}]} trailing',
        'preface {"tasks":[{"tool":"kb_search","input":"policy"}]}',
    ):
        assert call("plan", plan_text=malformed, question="policy?", java_run_id=run_id, allowed_tools="kb_search")["status"] == "FAILED"
    assert json.loads(plan["requests"][0])["callId"] == run_id + ":initial:1"
    empty_plan = call("plan", plan_text='<think>no authorized read-only task</think>{"tasks":[]}', question="q", java_run_id=run_id, allowed_tools="kb_search")
    assert empty_plan == {"status": "INSUFFICIENT_EVIDENCE", "requests": [], "answer": "", "citations": [], "usage": {}}
    assert call("plan", plan_text=json.dumps({"tasks": [{"tool": "web_search", "input": "x"}]}), question="q", java_run_id=run_id, allowed_tools="kb_search")["status"] == "FAILED"
    assert call("plan", plan_text=json.dumps({"tasks": [{"tool": "kb_search", "input": "x"}] * 5}), question="q", java_run_id=run_id, allowed_tools="kb_search")["status"] == "FAILED"
    task = call("workers_parse", item=plan["requests"][0])
    assert task["tool"] == "kb_search" and json.loads(task["body"])["runId"] == run_id

    sample = json.loads((HERE / "evidence-v1.sample.json").read_text())
    assert sample["chunkKey"] == "ragflow:" + ":".join(
        (sample["datasetId"], sample["docId"], sample["chunkId"]))
    assert sample["citationId"] == "kb:" + sample["chunkKey"]
    assert sample["sourceId"] == "来源1" and sample["citation"] == "[来源1]"
    assert sample["route"] == "ragflow" and sample["untrusted"] is True
    citation = sample["citationId"]
    evidence = {key: sample[key] for key in ("citationId", "sourceId", "title", "content", "untrusted")}
    success = call("workers_result", status_code=200, body=json.dumps({"success": True, "code": "OK", "tool": "kb_search", "evidences": [evidence], "value": ""}), tool="kb_search")["result"]
    assert json.loads(success)["status"] == "OK"
    tool_fail = call("workers_result", status_code=503, body="", tool="kb_search")["result"]
    assert json.loads(tool_fail)["status"] == "FAILED"
    malformed = dict(evidence, untrusted=False)
    invalid_evidence = call("workers_result", status_code=200, body=json.dumps({"success": True, "code": "OK", "tool": "kb_search", "evidences": [malformed], "value": ""}), tool="kb_search")["result"]
    assert json.loads(invalid_evidence)["status"] == "FAILED"

    initial = call("initial", results=[success])
    assert initial["status"] == "READY" and initial["count"] == 1
    assert call("initial", results=[success, tool_fail])["status"] == "FAILED"
    empty_initial = call("initial", results=[json.dumps({"status": "OK", "tool": "kb_search", "evidences": [], "value": ""})])
    assert empty_initial["status"] == "INSUFFICIENT_EVIDENCE"

    citation_a = "kb:ragflow:dataset-1:document-a:chunk-a"
    citation_b = "kb:ragflow:dataset-1:document-b:chunk-b"
    citation_c = "kb:ragflow:dataset-1:document-c:chunk-c"
    evidence_a = dict(evidence, citationId=citation_a, title="A", content="evidence A")
    evidence_b = dict(evidence, citationId=citation_b, title="B", content="evidence B")
    evidence_c = dict(evidence, citationId=citation_c, title="C", content="evidence C")
    worker_ab = call("workers_result", status_code=200, body=json.dumps({"success": True, "code": "OK", "tool": "kb_search", "evidences": [evidence_a, evidence_b], "value": ""}), tool="kb_search")["result"]
    worker_bc = call("workers_result", status_code=200, body=json.dumps({"success": True, "code": "OK", "tool": "kb_search", "evidences": [evidence_b, evidence_c], "value": ""}), tool="kb_search")["result"]
    deduplicated = call("initial", results=[worker_ab, worker_bc])
    deduplicated_evidence = json.loads(deduplicated["context"])["evidences"]
    assert deduplicated["status"] == "READY" and deduplicated["count"] == 2
    assert [item["citationId"] for item in deduplicated_evidence] == [citation_a, citation_b, citation_c]
    assert [item["sourceId"] for item in deduplicated_evidence] == ["来源1", "来源2", "来源3"]

    selected_ca = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "C first [来源3], then A [来源1].", "citations": [citation_c, citation_a]}), context=deduplicated["context"])
    assert selected_ca["status"] == "SUCCEEDED" and selected_ca["citations"] == [citation_c, citation_a]
    assert selected_ca["answer"] == "C first [来源1], then A [来源2]."
    reordered_ca = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "C first [来源3], then A [来源1].", "citations": [citation_a, citation_c]}), context=deduplicated["context"])
    assert reordered_ca["status"] == "SUCCEEDED" and reordered_ca["citations"] == [citation_c, citation_a]
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Wrong ID [来源3].", "citations": [citation_a]}), context=deduplicated["context"])["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Unknown [来源4].", "citations": [citation_c]}), context=deduplicated["context"])["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Duplicate [来源1] [来源2].", "citations": [citation_c, citation_c]}), context=deduplicated["context"])["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Foreign [来源1] [来源2].", "citations": [citation_c, "kb:ragflow:foreign:document:chunk"]}), context=deduplicated["context"])["status"] == "FAILED"
    literal_example = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "The literal [来源N] is a format example; evidence A [来源1].", "citations": [citation_a]}),
            context=deduplicated["context"])
    assert literal_example["status"] == "SUCCEEDED" and literal_example["answer"] == "The literal 来源编号 is a format example; evidence A [来源1]."
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "Malformed [来源X] beside evidence A [来源1].", "citations": [citation_a]}),
            context=deduplicated["context"])["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "No source [来源1].", "citations": [citation_a]}), context=empty_initial["context"])["status"] == "INSUFFICIENT_EVIDENCE"

    nine = [{"sourceId": "来源" + str(index), "citationId": "kb:ragflow:ds:doc:chunk" + str(index)}
            for index in range(1, 10)]
    first_used = [1, 2, 6, 7, 3, 5]
    live_shape = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2] C [来源2] D [来源6] E [来源7] F [来源3] G [来源5]",
            "citations": [nine[index - 1]["citationId"] for index in first_used]}),
            context=json.dumps({"evidences": nine}))
    assert live_shape["status"] == "SUCCEEDED"
    assert live_shape["citations"] == [nine[index - 1]["citationId"] for index in first_used]
    assert live_shape["answer"] == "A [来源1] B [来源2] C [来源2] D [来源3] E [来源4] F [来源5] G [来源6]"
    unordered_shape = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2] C [来源2] D [来源6] E [来源7] F [来源3] G [来源5]",
            "citations": [nine[index - 1]["citationId"] for index in reversed(first_used)]}),
            context=json.dumps({"evidences": nine}))
    assert unordered_shape == live_shape
    source_label_shape = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2] C [来源2] D [来源6] E [来源7] F [来源3] G [来源5]",
            "citations": ["来源" + str(index) for index in reversed(first_used)]}),
            context=json.dumps({"evidences": nine}))
    assert source_label_shape == live_shape
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2]", "citations": ["来源1", nine[1]["citationId"]]}),
            context=json.dumps({"evidences": nine}))["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2]", "citations": ["来源1", "来源10"]}),
            context=json.dumps({"evidences": nine}))["status"] == "FAILED"

    review = call("review", review_text=json.dumps({"verdict": "REVISE", "followups": [{"tool": "kb_search", "input": "more"}]}), context=initial["context"], initial_count=1, java_run_id=run_id, allowed_tools="kb_search")
    assert review["status"] == "READY" and len(review["requests"]) == 1
    assert json.loads(review["requests"][0])["callId"] == run_id + ":revision:1"
    over_budget = call("review", review_text=json.dumps({"verdict": "REVISE", "followups": [{"tool": "kb_search", "input": "more"}]}), context=initial["context"], initial_count=4, java_run_id=run_id, allowed_tools="kb_search")
    assert over_budget["status"] == "INSUFFICIENT_EVIDENCE"
    merged = call("merged", context=initial["context"], results=[success], revision_requests=review["requests"])
    assert merged["status"] == "READY" and len(json.loads(merged["context"])["evidences"]) == 1
    enough = call("review", review_text='{"verdict":"SUFFICIENT"}', context=initial["context"], initial_count=1, java_run_id=run_id, allowed_tools="kb_search")
    assert enough["status"] == "READY" and enough["requests"] == []
    thinking_review = call("review", review_text='<think>evidence is enough</think>{"verdict":"SUFFICIENT"}', context=initial["context"], initial_count=1, java_run_id=run_id, allowed_tools="kb_search")
    assert thinking_review["status"] == "READY"
    assert enough["needs_revision"] == "NO" and review["needs_revision"] == "YES"
    assert call("merged", context=initial["context"], results=[], revision_requests=[])["status"] == "READY"

    answer = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "The policy says so [来源1].", "citations": [citation]}), context=merged["context"])
    assert answer["status"] == "SUCCEEDED" and answer["citations"] == [citation]
    thinking_answer = call("final", synthesis_text='<think>use only evidence</think>' + json.dumps({"status": "SUCCEEDED", "answer": "The policy says so [来源1].", "citations": [citation]}), context=merged["context"])
    assert thinking_answer["status"] == "SUCCEEDED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Uncited", "citations": [citation]}), context=merged["context"])["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Wrong [来源1]", "citations": ["kb:ragflow:other:doc:chunk"]}), context=merged["context"])["status"] == "FAILED"
    assert call("final", synthesis_text='{"status":"INSUFFICIENT_EVIDENCE"}', context=merged["context"])["status"] == "INSUFFICIENT_EVIDENCE"


if __name__ == "__main__":
    verify_graph()
    verify_contract()
    print("Dify DSL graph and Evidence v1 contract checks passed")
