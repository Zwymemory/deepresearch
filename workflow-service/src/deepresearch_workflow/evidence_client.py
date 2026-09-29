"""Calls B's authenticated evidence protocol; all verifier requests use A's ledger."""

from __future__ import annotations

import hashlib

from .agent_budget import canonical
from .agent_protocol import ModelRequest
from .graph import WorkflowExecutionError


class HttpEvidenceBackend:
    def __init__(self, *, client, java_base_url, service_tokens):
        self.client, self.base, self.tokens = client, java_base_url.rstrip("/"), service_tokens

    def identifiers(self, state, task, call_id):
        return {
            "project_id": state["context_snapshot"]["agent_scope"]["project_id"],
            "run_id": state["run_id"],
            "task_id": task["task_id"],
            "call_id": call_id,
            "claim_token": state["claim_token"],
        }

    async def post(self, path, payload):
        response = await self.client.post(
            self.base + path,
            json=payload,
            headers={"Authorization": self.tokens.authorization_header()},
            timeout=30,
        )
        if response.status_code >= 400:
            # Stable rejection is an observation; the ledger handles transport ambiguity.
            try:
                code = response.json().get("errorCode") or response.json().get("code")
            except Exception:
                code = None
            return {
                "errorCode": code
                if isinstance(code, str) and len(code) <= 64
                else "EVIDENCE_REQUEST_REJECTED"
            }
        value = response.json()
        if not isinstance(value, dict):
            raise WorkflowExecutionError(
                "证据服务返回格式错误", error_code="EVIDENCE_PROTOCOL_INVALID"
            )
        return value

    async def read(self, state, task, call_id, source_id):
        data = await self.post(
            "/internal/agent/evidence/read",
            {"identifiers": self.identifiers(state, task, call_id), "source_id": source_id},
        )
        if data.get("errorCode"):
            return data
        if data.get("record_type") != "Evidence":
            raise WorkflowExecutionError(
                "原文读取缺少 Evidence", error_code="EVIDENCE_PROTOCOL_INVALID"
            )
        return {"records": [data]}

    async def check(self, state, task, call_id, claims, gateway):
        identifiers = self.identifiers(state, task, call_id)
        current = state.get("packet", {})
        investigation_id = current.get("investigation_id")
        if investigation_id:
            current = await self.post(
                "/internal/agent/evidence/investigations",
                {"identifiers": identifiers, "investigation_id": investigation_id},
            )
            if current.get("errorCode"):
                return current
        parent = current.get("check_id")
        round_number = current.get("dispute_round", -1) + 1
        if round_number > 2:
            return {"gaps": ["争议补查上限已到"], "errorCode": "DISPUTE_LIMIT"}
        original = current.get("claim_specs")
        if original and canonical(original) != canonical(claims):
            return {"gaps": ["补查必须保持原结论的适用条件"], "errorCode": "CLAIM_SCOPE_CHANGED"}
        available = {e["evidence_id"] for e in state.get("evidence", [])}
        selected = (
            state.get("selected_evidence_ids") or task.get("evidence_ids") or sorted(available)
        )
        if set(selected) - available:
            return {"errorCode": "EVIDENCE_NOT_IN_CURRENT_RUN"}
        # Explicitly carry prior contrary material. B independently computes this
        # set from its immutable check chain, so a client cannot omit it.
        evidence_ids = list(dict.fromkeys([*current.get("required_evidence_ids", []), *selected]))
        # B persists capacity failures. Rejecting locally would let a later
        # subset erase newly encountered material from the authoritative report.
        prepared = await self.post(
            "/internal/agent/evidence/checks/prepare",
            {
                "identifiers": identifiers,
                "claims": claims,
                "evidence_ids": evidence_ids,
                "dispute_round": round_number,
                "parent_check_id": parent,
                "investigation_id": investigation_id,
            },
        )
        if prepared.get("errorCode"):
            return prepared
        if prepared["requires_model"]:
            # B owns the request builder/parser. Absence is a visible integration error.
            from .evidence_check import (
                SYSTEM,
                checked_request,
                parse_verifier_response,
                response_schema,
            )

            checked_request(prepared["request"], prepared["request_sha256"])
            request = ModelRequest(
                name="EvidenceCheck",
                instruction=SYSTEM,
                payload=prepared["request"],
                schema=response_schema(prepared["request"]),
                request_binding={
                    "check_id": prepared["check_id"],
                    "request_sha256": prepared["request_sha256"],
                },
            )
            model_id = (
                "model:agent:check-"
                + hashlib.sha256(prepared["check_id"].encode()).hexdigest()[:32]
            )
            result = await gateway.model_call(
                model_id,
                "CHECK",
                request,
                lambda value: parse_verifier_response(
                    canonical(value), prepared["request"], prepared["request_sha256"]
                ),
            )
            checked = await self.post(
                "/internal/agent/evidence/checks/complete",
                {
                    "identifiers": identifiers,
                    "check_id": prepared["check_id"],
                    "model_call_id": model_id,
                    "response": result.value,
                },
            )
        else:
            checked = prepared["immediate_result"]
        if checked.get("errorCode"):
            return checked
        check_ids = list(dict.fromkeys([*current.get("check_ids", []), prepared["check_id"]]))
        packet = await self.post(
            "/internal/agent/evidence/packets", {"identifiers": identifiers, "check_ids": check_ids}
        )
        if packet.get("errorCode"):
            return packet
        return {
            **checked,
            "packet_id": packet["packet_id"],
            "check_id": prepared["check_id"],
            "check_ids": check_ids,
            "dispute_round": round_number,
            "claim_specs": claims,
            "investigation_id": prepared.get("investigation_id"),
            "required_evidence_ids": prepared.get("required_evidence_ids", evidence_ids),
            "gaps": packet.get("gaps", []),
        }

    async def publish(self, state, task, call_id, decision):
        return await self.post(
            "/internal/agent/publication",
            {"identifiers": self.identifiers(state, task, call_id)},
        )
