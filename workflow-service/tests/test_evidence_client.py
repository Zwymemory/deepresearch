"""Wire/parser compatibility with a committed B protocol; no real model/source claims."""

import asyncio
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway, canonical
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelResult
from deepresearch_workflow.evidence_client import HttpEvidenceBackend

from .test_agent_runtime import LedgerSubstitute


def _read_version_fixture():
    path = (
        Path(__file__).resolve().parents[2]
        / "testdata/agent-foundation/evidence/version-difference.json"
    )
    return json.loads(path.read_text(encoding="utf-8"))


@pytest.mark.parametrize("protocol", ["evidence-check/1", "evidence-check/2"])
async def test_peer_verifier_request_and_model_receipt_bind_exact_response_and_current_claim(
    protocol,
):
    pytest.importorskip(
        "deepresearch_workflow.evidence_check", reason="committed peer source bundle required"
    )
    fixture = await asyncio.to_thread(_read_version_fixture)
    record = next(r for r in fixture["records"] if r["record_type"] == "Evidence")
    specs = [
        {
            "text": "Version-scoped claim",
            "kind": "factual",
            "applicability": record["applicability"],
        }
    ]
    prepared = {
        "protocol_version": protocol,
        "check_id": "check-peer",
        "claims": [{"claim_id": "claim-peer", **specs[0]}],
        "evidence": [record],
        "dispute_round": 0,
        "parent_check_id": None,
    }
    if protocol == "evidence-check/2":
        prepared.update(investigation_id="server-investigation", prior_relations=[])
    text = record["snapshot"]["text"]
    proposal = {
        "claims": [
            {
                "claim_id": "claim-peer",
                "relations": [
                    {
                        "evidence_id": record["evidence_id"],
                        "relation": "supports",
                        "quote": text,
                        "reason": "Synthetic complete paragraph",
                    }
                ],
                "limitations": [],
            }
        ],
        "follow_up_actions": [],
    }
    calls = []
    ledger = LedgerSubstitute()

    class Model:
        async def invoke(self, request):
            assert request.name == "EvidenceCheck" and request.payload == prepared
            return ModelResult(value=proposal)

    async def guard():
        pass

    gateway = AgentBudgetGateway(
        run_id="wf-test",
        claim_token="current-claim",
        budget=AgentRunBudget(runtime="agent"),
        ledger=ledger,
        model=Model(),
        guard=guard,
    )

    def transport(request):
        payload = json.loads(request.content)
        calls.append((request.url.path, payload))
        assert payload["identifiers"]["claim_token"] == "current-claim"
        if request.url.path.endswith("prepare"):
            return httpx.Response(
                200,
                json={
                    "check_id": "check-peer",
                    "request_sha256": hashlib.sha256(canonical(prepared).encode()).hexdigest(),
                    "request": prepared,
                    "requires_model": True,
                    "immediate_result": None,
                },
            )
        if request.url.path.endswith("complete"):
            assert payload["response"] == proposal
            binding = ledger.rows[payload["model_call_id"]]["result"]["request_binding"]
            assert (
                binding["check_id"] == "check-peer"
                and binding["response_sha256"]
                == hashlib.sha256(canonical(proposal).encode()).hexdigest()
            )
            return httpx.Response(
                200,
                json={
                    "records": [
                        {
                            "record_type": "Claim",
                            "claim_id": "claim-peer",
                            "decision_status": "supported",
                        }
                    ],
                    "follow_up_actions": [],
                },
            )
        return httpx.Response(200, json={"packet_id": "packet-peer", "gaps": []})

    state = {
        "run_id": "wf-test",
        "claim_token": "current-claim",
        "context_snapshot": {"agent_scope": {"project_id": "project-test"}},
        "evidence": [record],
        "packet": {},
    }
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        backend = HttpEvidenceBackend(
            client=client,
            java_base_url="http://server.test",
            service_tokens=SimpleNamespace(authorization_header=lambda: "Bearer fixture-service"),
        )
        result = await backend.check(
            state, {"task_id": "task-test"}, "tool-" + "a" * 32, specs, gateway
        )
    assert (
        len(calls) == 3 and result["claim_specs"] == specs and result["check_ids"] == ["check-peer"]
    )


async def test_supplement_cannot_silently_change_claim_scope_or_reset_rounds():
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: pytest.fail("must not dispatch"))
    ) as client:
        backend = HttpEvidenceBackend(
            client=client,
            java_base_url="http://server.test",
            service_tokens=SimpleNamespace(authorization_header=lambda: "Bearer fixture-service"),
        )
        state = {
            "run_id": "wf-test",
            "claim_token": "current-claim",
            "context_snapshot": {"agent_scope": {"project_id": "project-test"}},
            "packet": {
                "check_id": "old",
                "dispute_round": 0,
                "claim_specs": [{"text": "original"}],
            },
        }
        result = await backend.check(
            state, {"task_id": "task"}, "call", [{"text": "changed"}], None
        )
        assert result["errorCode"] == "CLAIM_SCOPE_CHANGED"
        state["packet"]["dispute_round"] = 2
        result = await backend.check(
            state, {"task_id": "task"}, "call", [{"text": "original"}], None
        )
        assert result["errorCode"] == "DISPUTE_LIMIT"


@pytest.mark.parametrize("selected", [[], ["new-1", "new-2", "new-3", "new-4"]])
async def test_cumulative_counterevidence_overflow_is_explicit_and_never_truncated(selected):
    calls = []
    current = {
        "investigation_id": "server-investigation",
        "check_id": "prior-check",
        "dispute_round": 0,
        "claim_specs": [{"text": "Original"}],
        "required_evidence_ids": ["counter"],
        "check_ids": ["prior-check"],
    }

    def transport(request):
        calls.append(request.url.path)
        if request.url.path.endswith("investigations"):
            return httpx.Response(200, json=current)
        assert request.url.path.endswith("prepare")
        assert set(json.loads(request.content)["evidence_ids"]) == {
            "counter",
            "new-1",
            "new-2",
            "new-3",
            "new-4",
        }
        return httpx.Response(409, json={"errorCode": "EVIDENCE_CAPACITY_EXCEEDED"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        backend = HttpEvidenceBackend(
            client=client,
            java_base_url="http://server.test",
            service_tokens=SimpleNamespace(authorization_header=lambda: "Bearer substitute"),
        )
        state = {
            "run_id": "run",
            "claim_token": "claim",
            "context_snapshot": {"agent_scope": {"project_id": "project"}},
            "packet": current,
            "evidence": [
                {"evidence_id": i} for i in ["counter", "new-1", "new-2", "new-3", "new-4"]
            ],
            "selected_evidence_ids": selected,
        }
        result = await backend.check(
            state, {"task_id": "task"}, "call", [{"text": "Original"}], None
        )
    assert result["errorCode"] == "EVIDENCE_CAPACITY_EXCEEDED" and len(calls) == 2


async def test_independent_investigation_selects_relevant_material_and_carries_required_originals():
    payloads = []
    current = {
        "investigation_id": "server-investigation",
        "check_id": "prior-check",
        "dispute_round": 0,
        "claim_specs": [{"text": "Original"}],
        "required_evidence_ids": ["counter"],
        "check_ids": ["prior-check"],
    }

    def transport(request):
        body = json.loads(request.content)
        payloads.append((request.url.path, body))
        if request.url.path.endswith("investigations"):
            return httpx.Response(200, json=current)
        if request.url.path.endswith("prepare"):
            assert body["evidence_ids"] == ["counter", "new-material"]
            assert body["parent_check_id"] == "prior-check" and body["dispute_round"] == 1
            assert body["investigation_id"] == "server-investigation"
            return httpx.Response(
                200,
                json={
                    "check_id": "new-check",
                    "requires_model": False,
                    "immediate_result": {"records": []},
                    "investigation_id": "server-investigation",
                    "required_evidence_ids": ["counter", "new-material"],
                },
            )
        return httpx.Response(200, json={"packet_id": "packet", "gaps": ["Conflict remains"]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        backend = HttpEvidenceBackend(
            client=client,
            java_base_url="http://server.test",
            service_tokens=SimpleNamespace(authorization_header=lambda: "Bearer substitute"),
        )
        state = {
            "run_id": "run",
            "claim_token": "claim",
            "context_snapshot": {"agent_scope": {"project_id": "project"}},
            "packet": current,
            "evidence": [
                {"evidence_id": i}
                for i in ["counter", "new-material", "unrelated-1", "unrelated-2", "unrelated-3"]
            ],
            "selected_evidence_ids": ["new-material"],
        }
        result = await backend.check(
            state, {"task_id": "task"}, "call", [{"text": "Original"}], None
        )
    assert result["check_ids"] == ["prior-check", "new-check"] and result["gaps"]
    assert result["required_evidence_ids"] == ["counter", "new-material"] and len(payloads) == 3


async def test_publication_requests_all_server_investigations_without_claim_selection():
    def transport(request):
        body = json.loads(request.content)
        assert request.url.path == "/internal/agent/publication" and set(body) == {"identifiers"}
        return httpx.Response(
            200,
            json={
                "approved": True,
                "terminal_status": "INSUFFICIENT_EVIDENCE",
                "report_status": "partial",
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        backend = HttpEvidenceBackend(
            client=client,
            java_base_url="http://server.test",
            service_tokens=SimpleNamespace(authorization_header=lambda: "Bearer substitute"),
        )
        result = await backend.publish(
            {
                "run_id": "run",
                "claim_token": "claim",
                "context_snapshot": {"agent_scope": {"project_id": "project"}},
                "packet": {},
            },
            {"task_id": "task"},
            "call",
            None,
        )
    assert result["terminal_status"] == "INSUFFICIENT_EVIDENCE"
