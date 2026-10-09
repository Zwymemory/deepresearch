"""Server-owned saved progress: checked before use, never current factual evidence."""

from __future__ import annotations

import copy
import re

import httpx

from .graph import WorkflowExecutionError


def frozen_progress(context):
    progress = context.get("prior_progress")
    binding = context.get("prior_progress_binding")
    if progress is None and binding is None:
        return None
    if (
        not isinstance(progress, dict)
        or not isinstance(binding, dict)
        or progress.get("schema_version") != "research-progress-context/1"
        or progress.get("context_kind") != "prior_progress"
        or progress.get("trusted_as_evidence") is not False
        or not isinstance(progress.get("records"), list)
        or not 1 <= len(progress["records"]) <= 3
        or not isinstance(binding.get("project_id"), str)
        or progress.get("project_id") != binding["project_id"]
        or not isinstance(binding.get("projection_sha256"), str)
        or not re.fullmatch(r"[0-9a-f]{64}", binding["projection_sha256"])
        or type(binding.get("canonical_bytes")) is not int
        or not 0 < binding["canonical_bytes"] <= 16384
    ):
        raise WorkflowExecutionError(
            "Saved progress binding is invalid", error_code="RESEARCH_MEMORY_INVALID"
        )
    # Java owns its canonical hash. Do not reinterpret Python JSON bytes as that hash.
    return copy.deepcopy(progress), copy.deepcopy(binding)


def frozen_recall(context):
    recalled, binding = context.get("recalled_progress"), context.get("recalled_progress_binding")
    if recalled is None and binding is None:
        return None
    if (
        not isinstance(recalled, dict)
        or not isinstance(binding, dict)
        or recalled.get("schema_version") != "research-recall-context/1"
        or recalled.get("context_kind") != "recalled_progress"
        or recalled.get("trusted_as_evidence") is not False
        or not isinstance(recalled.get("records"), list)
        or not 0 <= len(recalled["records"]) <= 3
        or not isinstance(binding.get("project_id"), str)
        or not isinstance(binding.get("projection_sha256"), str)
        or not re.fullmatch(r"[0-9a-f]{64}", binding["projection_sha256"])
        or type(binding.get("canonical_bytes")) is not int
        or not 0 < binding["canonical_bytes"] <= 8192
    ):
        raise WorkflowExecutionError(
            "Recalled progress binding is invalid", error_code="RESEARCH_MEMORY_INVALID"
        )
    return copy.deepcopy(recalled), copy.deepcopy(binding)


def frozen_learning(context):
    conversation, binding = (
        context.get("conversation_context"),
        context.get("learning_memory_binding"),
    )
    expected = (
        isinstance(conversation, dict)
        and conversation.get("schema_version") == "conversation-referents/2"
    )
    if not expected and binding is None:
        return None
    from .conversation_context import checked_conversation

    try:
        if (
            not expected
            or not isinstance(binding, dict)
            or not isinstance(binding.get("project_id"), str)
            or not isinstance(binding.get("projection_sha256"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", binding["projection_sha256"])
            or type(binding.get("canonical_bytes")) is not int
            or not 0 < binding["canonical_bytes"] <= 32768
        ):
            raise ValueError("invalid")
        return checked_conversation(conversation), copy.deepcopy(binding)
    except (ValueError, TypeError, KeyError):
        raise WorkflowExecutionError(
            "Learning memory binding is invalid", error_code="RESEARCH_MEMORY_INVALID"
        ) from None


class HttpProgressMemoryClient:
    def __init__(self, *, client, java_base_url, service_tokens, timeout_seconds=20):
        self.client, self.base, self.tokens = client, java_base_url.rstrip("/"), service_tokens
        self.timeout = timeout_seconds

    async def validate(self, run_id, claim_token, context):
        result = None
        for endpoint, selected in [
            ("/validate", frozen_progress(context)),
            ("/recall/validate", frozen_recall(context)),
            ("/learning/validate", frozen_learning(context)),
        ]:
            if selected is not None:
                result = await self._validate(run_id, claim_token, selected[1], endpoint)
        return result

    async def _validate(self, run_id, claim_token, binding, endpoint):
        try:
            response = await self.client.post(
                self.base + "/internal/agent/memory" + endpoint,
                headers={"Authorization": self.tokens.authorization_header()},
                json={
                    "runId": run_id,
                    "claimToken": claim_token,
                    "projectionSha256": binding["projection_sha256"],
                },
                timeout=self.timeout,
                follow_redirects=False,
            )
        except httpx.HTTPError:
            raise WorkflowExecutionError(
                "Saved progress validation unavailable", error_code="RESEARCH_MEMORY_UNAVAILABLE"
            ) from None
        try:
            data = response.json()
        except ValueError:
            data = {}
        if response.status_code != 200:
            code = data.get("errorCode") if isinstance(data, dict) else None
            allowed = {
                "RESEARCH_MEMORY_" + name
                for name in (
                    "NOT_FOUND",
                    "UNAVAILABLE",
                    "INVALID",
                    "REVOKED",
                    "CLAIM_INVALID",
                    "INPUT_TOO_LARGE",
                )
            }
            raise WorkflowExecutionError(
                "Saved progress cannot be used; reload the project",
                error_code=code if code in allowed else "RESEARCH_MEMORY_UNAVAILABLE",
            )
        if (
            not isinstance(data, dict)
            or data.get("projectId") != binding["project_id"]
            or data.get("projectionSha256") != binding["projection_sha256"]
            or not isinstance(data.get("checkedAt"), str)
        ):
            raise WorkflowExecutionError(
                "Saved progress validation identity mismatch", error_code="RESEARCH_MEMORY_INVALID"
            )
        return data
