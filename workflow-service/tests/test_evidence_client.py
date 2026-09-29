"""Wire/parser compatibility with a committed B protocol; no real model/source claims."""

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


async def test_peer_verifier_request_and_model_receipt_bind_exact_response_and_current_claim():
    pytest.importorskip(
        "deepresearch_workflow.evidence_check", reason="committed peer source bundle required"
    )
    fixture = json.loads(
        (
            Path(__file__).resolve().parents[2]
            / "testdata/agent-foundation/evidence/version-difference.json"
        ).read_text()
    )
    record = next(r for r in fixture["records"] if r["record_type"] == "Evidence")
    specs = [
        {
            "text": "Version-scoped claim",
            "kind": "factual",
            "applicability": record["applicability"],
        }
    ]
    prepared = {
        "protocol_version": "evidence-check/1",
        "check_id": "check-peer",
        "claims": [{"claim_id": "claim-peer", **specs[0]}],
        "evidence": [record],
        "dispute_round": 0,
        "parent_check_id": None,
    }
    text = record["snapshot"]["text"]
    proposal = {
        "claims": [
            {
                "claim_id": "claim-peer",
                "relations": [
                    {
                        "evidence_id": record["evidence_id"],
                        "relation": "supports",
                        "quote": {
                            "start": 0,
                            "end": len(text),
                            "text": text,
                            "sha256": hashlib.sha256(text.encode()).hexdigest(),
                        },
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
