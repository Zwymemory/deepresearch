"""Calls B's authenticated evidence protocol; all verifier requests use A's ledger."""

from __future__ import annotations

import hashlib

from .agent_budget import canonical
from .agent_decision_instruction import CHECK_CAPACITY_POLICIES, POLICY_VERSION, SUPPORTED_POLICIES
from .agent_protocol import ModelRequest
from .graph import ModelCallError, WorkflowExecutionError


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
        policy = state.get("instruction_policy")
        if policy is not None and policy not in SUPPORTED_POLICIES:
            raise WorkflowExecutionError(
                "Agent instruction policy unknown",
                error_code="AGENT_INSTRUCTION_POLICY_INVALID",
            )
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
                **(
                    {
                        "claims_contract": state["claims_contract"],
                        "requirements_ref": state["original_requirements"]["manifest_sha256"],
                        "claim_references": [
                            {k: r[k] for k in ("requirement_id", "criterion_id")}
                            for r in state["claim_references"]
                        ],
                    }
                    if state.get("claims_contract") == "agent-obligation-claims/1"
                    else {}
                ),
            },
        )
        if prepared.get("errorCode"):
            return prepared
        if prepared["requires_model"]:
            # B owns the request builder/parser. Absence is a visible integration error.
            from .evidence_check import (
                checked_request,
                parse_verifier_response,
                response_schema,
                verifier_instruction,
            )

            checked_request(prepared["request"], prepared["request_sha256"])
            request = ModelRequest(
                name="EvidenceCheck",
                instruction=verifier_instruction(prepared["request"]),
                payload=prepared["request"],
                schema=response_schema(prepared["request"]),
                max_output_tokens=4096 if policy in CHECK_CAPACITY_POLICIES else 1024,
                request_binding={
                    "check_id": prepared["check_id"],
                    "request_sha256": prepared["request_sha256"],
                    **({"instruction_policy": policy} if policy in CHECK_CAPACITY_POLICIES else {}),
                },
            )
            if (
                state.get("context_snapshot", {})
                .get("project_summary_policy", {})
                .get("projection_encoding")
                == "shared-context-values/1"
            ):
                request = request.model_copy(
                    update={
                        "instruction": request.instruction
                        + "\nJSON outer shape (empty arrays illustrate syntax only; include "
                        "EVERY requested claim row in the actual result): "
                        '{"claims": [], "follow_up_actions": [], "planning_alignment": '
                        '{"status": "complete", "reason": "Explain the plan assessment"}}. '
                        "The three fields belong to the SAME top-level object. Close the "
                        "claims array with ] before follow_up_actions; close the outer object "
                        "with } ONLY after planning_alignment. Do not emit standalone claim "
                        "objects or separate objects for follow-up actions. "
                        "JSON syntax: return exactly ONE enclosing object. Put all claim "
                        "rows inside its claims array; never append another object or a comma "
                        "after the enclosing object. Escape embedded double quotes, backslashes "
                        "and newlines inside quote strings. Preserve the decoded exact source "
                        "text; JSON escaping must not change the quotation. "
                        "planning_alignment assesses only whether the frozen plan contains ALL "
                        "original substantive questions and classifies source/output constraints "
                        "correctly. Do not mark the PLAN incomplete merely because the currently "
                        "read evidence supports only some criteria, because few sources were "
                        "read, or because some claims lack support. Report those evidence gaps "
                        "through relations, answer_alignment, source_alignment and follow-ups. "
                        "A genuinely omitted question, lost condition or misclassified "
                        "citation/output instruction still makes planning_alignment incomplete. "
                        "Judge every explicitly named failure condition separately: process "
                        "restart is not client connection/stream recovery. Model/tool attempt "
                        "reservation and replay do not alone establish idempotent creation of "
                        "a research task/run. Reject a claim that substitutes those adjacent "
                        "behaviors for the original requested behavior unless read originals "
                        "explicitly establish that relationship.",
                        "request_binding": {
                            **request.request_binding,
                            "context_encoding": "shared-context-values/1",
                        },
                    }
                )
                evidence_ids = [e["evidence_id"] for e in prepared["request"]["evidence"]]
                request = request.model_copy(
                    update={
                        "instruction": request.instruction
                        + f"\nFor EACH claim, relations must contain exactly {len(evidence_ids)} "
                        f"rows, one for EACH evidence_id in {canonical(evidence_ids)}. "
                        "Do not assign just one source to a claim and omit the other selected "
                        "sources. For an irrelevant source include an insufficient relation "
                        "with an exact complete paragraph. Keep reasons concise."
                    }
                )
            model_id = (
                "model:agent:check-"
                + hashlib.sha256(prepared["check_id"].encode()).hexdigest()[:32]
            )
            compact_quotes = policy == POLICY_VERSION
            if compact_quotes:
                from . import evidence_quotes

                request = request.model_copy(
                    update={
                        "payload": evidence_quotes.model_payload(
                            prepared["request"], prepared["request_sha256"]
                        ),
                        "result_schema": evidence_quotes.model_schema(prepared["request"]),
                        "instruction": evidence_quotes.instruction(
                            verifier_instruction(prepared["request"]), prepared["request"]
                        )
                        + "\nplanning_alignment assesses the frozen plan, not whether every "
                        "obligation has already been checked. Evidence gaps belong in the "
                        "individual relations and alignments. Return one enclosing JSON object.",
                        "request_binding": {
                            **request.request_binding,
                            "evidence_quote_encoding": evidence_quotes.encoding_for(
                                prepared["request"]
                            ),
                        },
                    }
                )
            try:
                if compact_quotes:

                    def expand(value):
                        return evidence_quotes.expand(
                            value, prepared["request"], prepared["request_sha256"]
                        )

                    result = await gateway.model_call(
                        model_id, "CHECK", request, expand, canonicalize=expand
                    )
                else:
                    result = await gateway.model_call(
                        model_id,
                        "CHECK",
                        request,
                        lambda value: parse_verifier_response(
                            canonical(value), prepared["request"], prepared["request_sha256"]
                        ),
                    )
            except WorkflowExecutionError as failed:
                non_retryable = (
                    isinstance(failed, ModelCallError) and failed.retryable is False
                ) or failed.error_code == "AGENT_MODEL_NOT_RETRYABLE"
                if policy not in CHECK_CAPACITY_POLICIES or not non_retryable:
                    raise
                # The MODEL receipt remains UNKNOWN with its measured usage. A
                # settled TOOL receipt makes this terminal observation replayable.
                return {
                    "errorCode": failed.error_code,
                    "non_retryable_check": True,
                    "check_call_id": call_id,
                    "check_id": prepared["check_id"],
                    "model_call_id": model_id,
                    "investigation_id": prepared.get("investigation_id"),
                    "request_sha256": prepared["request_sha256"],
                    "gaps": ["核查模型结果不可用且不可重试"],
                }
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
