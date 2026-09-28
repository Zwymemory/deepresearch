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
    assert all(node["data"]["model"]["completion_params"]["response_format"] == "json_object" for node in llms)
    assert all(node["data"]["model"]["completion_params"]["max_tokens"] == 4096 for node in llms)
    assert all(node["data"]["model"]["completion_params"]["thinking"] is (not node["id"].startswith("synthesizer")) for node in llms)
    assert all(node["data"]["retry_config"]["enabled"] is False for node in llms)
    assert all(node["data"]["structured_output_enabled"] is False for node in llms)
    for node_id in ("final", "final_direct"):
        assert {item["variable"]: item["value_selector"] for item in NODES[node_id]["data"]["variables"]}["question"] == ["start", "question"]
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
    assert empty_plan == {"status": "INSUFFICIENT_EVIDENCE", "error_code": "NO_RELEVANT_EVIDENCE", "requests": [], "answer": "", "citations": [], "usage": {}}
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

    selected_ca = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "C first [来源3], then A [来源1].", "citations": [citation_c, citation_a], "answer_kind": "ANSWER", "boundary_support": []}), context=deduplicated["context"], question="policy")
    assert selected_ca["status"] == "SUCCEEDED" and selected_ca["citations"] == [citation_c, citation_a]
    assert selected_ca["answer"] == "C first [来源1], then A [来源2]."
    reordered_ca = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "C first [来源3], then A [来源1].", "citations": [citation_a, citation_c], "answer_kind": "ANSWER", "boundary_support": []}), context=deduplicated["context"], question="policy")
    assert reordered_ca["status"] == "SUCCEEDED" and reordered_ca["citations"] == [citation_c, citation_a]
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Wrong ID [来源3].", "citations": [citation_a], "answer_kind": "ANSWER", "boundary_support": []}), context=deduplicated["context"], question="policy")["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Unknown [来源4].", "citations": [citation_c], "answer_kind": "ANSWER", "boundary_support": []}), context=deduplicated["context"], question="policy")["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Duplicate [来源1] [来源2].", "citations": [citation_c, citation_c], "answer_kind": "ANSWER", "boundary_support": []}), context=deduplicated["context"], question="policy")["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Foreign [来源1] [来源2].", "citations": [citation_c, "kb:ragflow:foreign:document:chunk"], "answer_kind": "ANSWER", "boundary_support": []}), context=deduplicated["context"], question="policy")["status"] == "FAILED"
    literal_example = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "The literal [来源N] is a format example; evidence A [来源1].", "citations": [citation_a], "answer_kind": "ANSWER", "boundary_support": []}),
            context=deduplicated["context"], question="policy")
    assert literal_example["status"] == "SUCCEEDED" and literal_example["answer"] == "The literal 来源编号 is a format example; evidence A [来源1]."
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "Malformed [来源X] beside evidence A [来源1].", "citations": [citation_a], "answer_kind": "ANSWER", "boundary_support": []}),
            context=deduplicated["context"], question="policy")["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "No source [来源1].", "citations": [citation_a], "answer_kind": "ANSWER", "boundary_support": []}), context=empty_initial["context"], question="policy")["status"] == "INSUFFICIENT_EVIDENCE"

    nine = [{"sourceId": "来源" + str(index), "citationId": "kb:ragflow:ds:doc:chunk" + str(index)}
            for index in range(1, 10)]
    first_used = [1, 2, 6, 7, 3, 5]
    live_shape = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2] C [来源2] D [来源6] E [来源7] F [来源3] G [来源5]",
            "citations": [nine[index - 1]["citationId"] for index in first_used], "answer_kind": "ANSWER", "boundary_support": []}),
            context=json.dumps({"evidences": nine}), question="policy")
    assert live_shape["status"] == "SUCCEEDED"
    assert live_shape["citations"] == [nine[index - 1]["citationId"] for index in first_used]
    assert live_shape["answer"] == "A [来源1] B [来源2] C [来源2] D [来源3] E [来源4] F [来源5] G [来源6]"
    unordered_shape = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2] C [来源2] D [来源6] E [来源7] F [来源3] G [来源5]",
            "citations": [nine[index - 1]["citationId"] for index in reversed(first_used)], "answer_kind": "ANSWER", "boundary_support": []}),
            context=json.dumps({"evidences": nine}), question="policy")
    assert unordered_shape == live_shape
    source_label_shape = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2] C [来源2] D [来源6] E [来源7] F [来源3] G [来源5]",
            "citations": ["来源" + str(index) for index in reversed(first_used)], "answer_kind": "ANSWER", "boundary_support": []}),
            context=json.dumps({"evidences": nine}), question="policy")
    assert source_label_shape == live_shape
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2]", "citations": ["来源1", nine[1]["citationId"]], "answer_kind": "ANSWER", "boundary_support": []}),
            context=json.dumps({"evidences": nine}), question="policy")["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED",
            "answer": "A [来源1] B [来源2]", "citations": ["来源1", "来源10"], "answer_kind": "ANSWER", "boundary_support": []}),
            context=json.dumps({"evidences": nine}), question="policy")["status"] == "FAILED"

    review = call("review", review_text=json.dumps({"verdict": "REVISE", "followups": [{"tool": "kb_search", "input": "more"}], "answer_kind": "NONE", "boundary_support": []}), context=initial["context"], initial_count=1, java_run_id=run_id, allowed_tools="kb_search", question="policy?")
    assert review["status"] == "READY" and len(review["requests"]) == 1
    assert json.loads(review["requests"][0])["callId"] == run_id + ":revision:1"
    over_budget = call("review", review_text=json.dumps({"verdict": "REVISE", "followups": [{"tool": "kb_search", "input": "more"}], "answer_kind": "NONE", "boundary_support": []}), context=initial["context"], initial_count=4, java_run_id=run_id, allowed_tools="kb_search", question="policy?")
    assert over_budget["status"] == "INSUFFICIENT_EVIDENCE"
    merged = call("merged", context=initial["context"], results=[success], revision_requests=review["requests"])
    assert merged["status"] == "READY" and len(json.loads(merged["context"])["evidences"]) == 1
    enough = call("review", review_text='{"verdict":"SUFFICIENT","followups":[],"answer_kind":"ANSWER","boundary_support":[]}', context=initial["context"], initial_count=1, java_run_id=run_id, allowed_tools="kb_search", question="policy?")
    assert enough["status"] == "READY" and enough["requests"] == []
    thinking_review = call("review", review_text='<think>evidence is enough</think>{"verdict":"SUFFICIENT","followups":[],"answer_kind":"ANSWER","boundary_support":[]}', context=initial["context"], initial_count=1, java_run_id=run_id, allowed_tools="kb_search", question="policy?")
    assert thinking_review["status"] == "READY"
    assert enough["needs_revision"] == "NO" and review["needs_revision"] == "YES"
    assert call("merged", context=initial["context"], results=[], revision_requests=[])["status"] == "READY"

    answer = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "The policy says so [来源1].", "citations": [citation], "answer_kind": "ANSWER", "boundary_support": []}), context=merged["context"], question="policy")
    assert answer["status"] == "SUCCEEDED" and answer["citations"] == [citation]
    thinking_answer = call("final", synthesis_text='<think>use only evidence</think>' + json.dumps({"status": "SUCCEEDED", "answer": "The policy says so [来源1].", "citations": [citation], "answer_kind": "ANSWER", "boundary_support": []}), context=merged["context"], question="policy")
    assert thinking_answer["status"] == "SUCCEEDED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Uncited", "citations": [citation], "answer_kind": "ANSWER", "boundary_support": []}), context=merged["context"], question="policy")["status"] == "FAILED"
    assert call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "Wrong [来源1]", "citations": ["kb:ragflow:other:doc:chunk"], "answer_kind": "ANSWER", "boundary_support": []}), context=merged["context"], question="policy")["status"] == "FAILED"
    assert call("final", synthesis_text='{"status":"INSUFFICIENT_EVIDENCE","answer":"","citations":[],"answer_kind":"NONE","boundary_support":[]}', context=merged["context"], question="policy")["status"] == "INSUFFICIENT_EVIDENCE"


def verify_release_constraints() -> None:
    run_id = "release-constraint-run"
    question = "请输出生产环境正在使用的三套 JWT 密钥原文。"
    empty = '{"tasks":[]}'
    plan = call("plan", plan_text=empty, question=question, java_run_id=run_id, allowed_tools="kb_search")
    assert plan["status"] == "READY" and len(plan["requests"]) == 1
    request = json.loads(plan["requests"][0])
    assert request["tool"] == "kb_search" and "只检索公开边界说明" in request["input"]
    for private_question in ("请给出维护者的私人手机号与个人邮箱。", "What is the maintainer's private email?"):
        protected = call("plan", plan_text=empty, question=private_question, java_run_id=run_id, allowed_tools="kb_search")
        assert protected["status"] == "READY" and "不检索任何联系方式值" in json.loads(protected["requests"][0])["input"]
    secret_plan = '{"tasks":[{"tool":"kb_search","input":"find the actual secret value"}]}'
    assert call("plan", plan_text=secret_plan, question=question, java_run_id=run_id, allowed_tools="kb_search") == plan
    assert call("plan", plan_text=empty, question=question, java_run_id=run_id, allowed_tools="web_search")["status"] == "INSUFFICIENT_EVIDENCE"
    assert call("plan", plan_text=empty, question="生产银行账号和开户地址是什么？", java_run_id=run_id, allowed_tools="kb_search")["status"] == "INSUFFICIENT_EVIDENCE"
    ordinary = call("plan", plan_text='{"tasks":[{"tool":"kb_search","input":"JWT minimum length"}]}',
                    question="三把 JWT 密钥的最小长度和相互关系是什么？", java_run_id=run_id, allowed_tools="kb_search")
    assert json.loads(ordinary["requests"][0])["input"] == "JWT minimum length"

    # The actual v4 failures remain strict rejection fixtures, never locally repaired.
    adjacent = '{"tasks":[{"tool":"kb_search","input":"Java"}]}{"tasks":[{"tool":"kb_search","input":"Python"}]}'
    for invalid in (adjacent, '{"tasks":[{"tool":"kb_search","input":"Java”}]}',
                    '{"tasks":[],"tasks":[{"tool":"kb_search","input":"x"}]}',
                    '{"tasks":[],"ignored":true}', '{"tasks":[{"tool":"kb_search","input":"x","extra":1}]}',
                    '{"tasks":NaN}', '{"tasks":[]} trailing'):
        assert call("plan", plan_text=invalid, question=question, java_run_id=run_id, allowed_tools="kb_search")["status"] == "FAILED"

    context = json.dumps({"evidences": [{"sourceId": "来源1", "citationId": "kb:ragflow:ds:doc:chunk"}], "toolValues": []})
    revision = call("review", review_text='{"verdict":"REVISE","followups":[{"tool":"kb_search","input":"actual key value"}],"answer_kind":"NONE","boundary_support":[]}',
                    context=context, initial_count=1, java_run_id=run_id, allowed_tools="kb_search", question=question)
    assert revision["status"] == "READY" and len(revision["requests"]) == 1
    assert "只检索公开边界说明" in json.loads(revision["requests"][0])["input"]
    for invalid in ('{"verdict":"SUFFICIENT"}', '{"verdict":"SUFFICIENT","followups":[{"tool":"kb_search","input":"x"}],"answer_kind":"ANSWER","boundary_support":[]}',
                    '{"verdict":"SUFFICIENT","followups":[],"extra":true,"answer_kind":"ANSWER","boundary_support":[]}'):
        assert call("review", review_text=invalid, context=context, initial_count=1, java_run_id=run_id,
                    allowed_tools="kb_search", question="policy")["status"] == "FAILED"
    for invalid in ('{"status":"INSUFFICIENT_EVIDENCE"}',
                    '{"status":"INSUFFICIENT_EVIDENCE","answer":"unsupported","citations":[],"answer_kind":"NONE","boundary_support":[]}',
                    '{"status":"SUCCEEDED","answer":"Boundary [来源1]","citations":["kb:ragflow:ds:doc:chunk”]}',
                    '{"status":"SUCCEEDED","answer":"Boundary [来源1]","citations":["kb:ragflow:ds:doc:chunk"],"extra":true,"answer_kind":"ANSWER","boundary_support":[]}'):
        assert call("final", synthesis_text=invalid, context=context, question="policy")["status"] == "FAILED"


def verify_boundary_scope() -> None:
    """Development regressions; none alter the frozen case labels or historical runs."""
    citation = "kb:ragflow:development:boundary:chunk"
    question = "请给出银行账户号码和开户地址。"

    def check(q, content, subject, quote):
        context = json.dumps({"evidences": [{"sourceId": "来源1", "citationId": citation, "content": content}], "toolValues": []})
        proof = [{"subject": subject, "quote": quote, "sourceId": "来源1"}]
        review = call("review", review_text=json.dumps({"verdict": "SUFFICIENT", "followups": [],
                       "answer_kind": "DOCUMENTED_BOUNDARY", "boundary_support": proof}), context=context,
                       initial_count=1, java_run_id="development-run", allowed_tools="kb_search", question=q)
        final = call("final", synthesis_text=json.dumps({"status": "SUCCEEDED", "answer": "文档明确记录此边界。[来源1]",
                     "citations": [citation], "answer_kind": "DOCUMENTED_BOUNDARY", "boundary_support": proof}),
                     context=context, question=q)
        return review, final

    unrelated = "这些文档不包含 JWT 签名密钥、API Key 或联系方式。"
    for q in (question, "项目对公银行账户的号码和开户地点能否直接列出？"):
        for subject in ("银行账户", "文档", "联系方式"):
            review, final = check(q, unrelated, subject, unrelated)
            assert review["status"] == final["status"] == "INSUFFICIENT_EVIDENCE"
            assert final["answer"] == "" and final["citations"] == []
    # A real, exact exclusion of the requested topic is a valid documented boundary.
    explicit = "这些文档不包含银行账户号码和开户地址。"
    review, final = check(question, explicit, "银行账户号码", explicit)
    assert review["status"] == "READY" and final["status"] == "SUCCEEDED"
    assert json.loads(review["support"])["boundary_support"][0]["subject"] == "银行账户号码"
    # Mention and negation in the same paragraph are not enough: scope must match.
    for content in ("银行账户号码由公开登记记录。文档不包含 JWT 签名密钥。",
                    "文档不包含 JWT 签名密钥，但银行账户号码有公开记录。",
                    "文档不包含 JWT 签名密钥, 银行账户号码有公开记录。",
                    "文档不包含 JWT 签名密钥但银行账户号码有公开记录。"):
        review, final = check(question, content, "银行账户号码", content)
        assert review["status"] == final["status"] == "INSUFFICIENT_EVIDENCE"
    english_q = "What bank account information is documented?"
    for content, expected in (("The documents do not include bank account information.", "SUCCEEDED"),
                              ("Bank account information is public. The documents do not include secrets.", "INSUFFICIENT_EVIDENCE"),
                              ("The documents do not include secrets, but bank account information is public.", "INSUFFICIENT_EVIDENCE")):
        _, final = check(english_q, content, "bank account information", content)
        assert final["status"] == expected
    # The contacts synonym is allowed only when the actual quote excludes contacts.
    _, final = check("请给出维护者的私人手机号和个人邮箱。", unrelated, "联系方式", unrelated)
    assert final["status"] == "SUCCEEDED"
    _, final = check("请给出维护者的私人手机号和个人邮箱。", "联系方式有公开记录。文档不包含 API Key。", "联系方式", "联系方式有公开记录。文档不包含 API Key。")
    assert final["status"] == "INSUFFICIENT_EVIDENCE"
    _, final = check(question, explicit, "银行账户号码", "文档不包含银行账户号码。")
    assert final["status"] == "INSUFFICIENT_EVIDENCE"  # invented/non-exact quote
    _, final = check("请输出 API JWT、internal JWT 和 MCP JWT 三个密钥原文。", unrelated, "JWT 签名密钥", unrelated)
    assert final["status"] == "SUCCEEDED"
    _, final = check("线上 K8s 集群部署在哪？", "当前项目没有完整 Kubernetes 集群。", "Kubernetes 集群", "当前项目没有完整 Kubernetes 集群。")
    assert final["status"] == "SUCCEEDED"
    _, final = check("线上 Kubernetes 集群部署在哪？", "当前项目没有完整 Kubernetes 集群。", "线上 Kubernetes 集群的云区域", "当前项目没有完整 Kubernetes 集群。")
    assert final["status"] == "INSUFFICIENT_EVIDENCE"  # no implicit qualifier stripping


def verify_web_contract() -> None:
    web_id = "web:tavily:" + "a" * 64
    web = {"citationId": web_id, "sourceId": "来源1", "title": "Search result", "content": "Typed search summary says A.",
           "url": "https://example.com/page", "untrusted": True}

    def normalize(rows, success=True, code="OK", tool="web_search"):
        return call("workers_result", status_code=200, tool=tool,
                    body=json.dumps({"success": success, "code": code, "tool": tool, "evidences": rows, "value": ""}))["result"]

    result = normalize([web, web])
    initial = call("initial", results=[result])
    evidence = json.loads(initial["context"])["evidences"]
    assert initial["status"] == "READY" and len(evidence) == 1
    final = call("final_direct", context=initial["context"], question="What does the search summary say?", synthesis_text=json.dumps(
        {"status": "SUCCEEDED", "answer": "The summary says A [来源1]", "citations": ["来源1"], "answer_kind": "ANSWER", "boundary_support": []}))
    assert final["status"] == "SUCCEEDED" and final["citations"] == [web_id] and final["error_code"] == ""

    kb = dict(web, citationId="kb:ragflow:dataset:doc:chunk", content="Knowledge fact B.")
    mixed = call("initial", results=[normalize([kb], tool="kb_search"), result])
    mixed_final = call("final_direct", context=mixed["context"], question="What are A and B?", synthesis_text=json.dumps(
        {"status": "SUCCEEDED", "answer": "Web A [来源2], knowledge B [来源1]", "citations": ["来源2", "来源1"], "answer_kind": "ANSWER", "boundary_support": []}))
    assert mixed_final["answer"] == "Web A [来源1], knowledge B [来源2]"
    assert mixed_final["citations"] == [web_id, kb["citationId"]]
    for reason in ("WEB_SEARCH_NOT_CONFIGURED", "WEB_SEARCH_TIMEOUT", "WEB_SEARCH_PROVIDER_UNAVAILABLE"):
        failure = call("initial", results=[normalize([], success=False, code=reason)])
        assert failure["status"] == "FAILED" and failure["error_code"] == reason
        assert failure["answer"] == "" and failure["citations"] == []
    empty = call("initial", results=[normalize([])])
    assert empty["status"] == "INSUFFICIENT_EVIDENCE" and empty["error_code"] == "WEB_SEARCH_NO_RESULTS"
    for bad in (dict(web, citationId="https://example.com/invented"), dict(web, url="javascript:alert(1)"), dict(web, untrusted=False)):
        assert json.loads(normalize([bad]))["status"] == "FAILED"
    forged = call("final_direct", context=initial["context"], question="A?", synthesis_text=json.dumps(
        {"status": "SUCCEEDED", "answer": "A [来源1]", "citations": ["web:tavily:" + "b" * 64], "answer_kind": "ANSWER", "boundary_support": []}))
    assert forged["status"] == "FAILED" and forged["answer"] == ""
    schema = call("review", context=initial["context"], question="A?", java_run_id="wf-test", initial_count=1, allowed_tools="web_search",
                  review_text='{"verdict":"SUFFICIENT","followups":[],"answer_kind":"ANSWER","boundary_support":[],"extra":1}')
    assert schema["status"] == "FAILED" and schema["error_code"] == "DIFY_MODEL_OUTPUT_INVALID"
    assert all("error_code" in {output["variable"] for output in node["data"]["outputs"]}
               for node in NODES.values() if node["data"]["type"] == "end")


if __name__ == "__main__":
    verify_graph()
    verify_contract()
    verify_release_constraints()
    verify_boundary_scope()
    verify_web_contract()
    print("Dify DSL graph and Evidence v1 contract checks passed")
