"""SQL admission and replay shared by decisions, checks and external tool operations."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Awaitable, Callable
from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError as JsonSchemaValidationError
from psycopg.types.json import Jsonb
from pydantic import ValidationError as PydanticValidationError
from referencing import Registry

from .agent_decision_instruction import POLICY_VERSION as POLICY_VERSION
from .agent_decision_instruction import SUPPORTED_POLICIES
from .agent_diagnostics import safe_check_code, safe_requirement_code, safe_segment_diagnostic
from .agent_json import FINISH_REASONS
from .agent_model import MODEL_RULES, AgentModel, AgentModelFailure, OpenAIAgentModel
from .agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from .agent_question_segments import PLANNER_VERSION, replay_declaration
from .agent_requirements import RequirementError
from .agent_schema_diagnostics import diagnostic as schema_failure_diagnostic
from .evidence_check import EvidenceCheckError
from .graph import (
    ModelCallError,
    RunBudgetExceededError,
    RunCancelledError,
    RunTimedOutError,
    StaleClaimError,
    WorkflowExecutionError,
)
from .ports import BudgetClaimConflictError


def canonical(value):
    return json.dumps(
        value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False
    )


# These are field names in the public Agent function contracts, not model-supplied
# values. A path outside this vocabulary is represented as an anonymous field.
SAFE_FIELDS = frozenset({
    "action", "answer", "applicability", "claims", "conditions", "criterion_bindings",
    "criterion_id", "evidence_ids", "gaps", "investigation_id", "kind", "query",
    "reason", "source_id", "status", "subject", "task_id", "tool", "valid_at",
    "value", "version",
    "requirements", "requirement_bindings", "question_spans", "start", "end", "text",
    "requirement_id",
    "segment_ids", "planner_contract", "continuation_contract", "requirements_ref",
    "obligations", "constraints", "role", "obligation_indices", "claims_contract",
})


def safe_issue_path(parts):
    names = [part for part in parts if type(part) is str and part in SAFE_FIELDS]
    return ".".join(names[:4]) or "unknown_field"


class ResultValidationError(Exception):
    """Internal stage marker. The original exception is never serialized or chained."""

    def __init__(self, error, stage):
        super().__init__("Agent result validation failed")
        self.error = error
        self.stage = stage


class SqlAgentLedger:
    def __init__(self, repository):
        self.repository = repository

    async def reserve(
        self,
        run_id,
        claim_token,
        key,
        kind,
        purpose,
        request_hash,
        input_reserved,
        output_reserved,
        budget,
    ):
        ambiguous = False
        async with self.repository.pool.connection() as conn:
            async with conn.transaction():
                stored = await self.repository._lock_active_budget_run(conn, run_id, claim_token)
                if not isinstance(stored, AgentRunBudget):
                    raise WorkflowExecutionError(
                        "该运行未启用 Agent 预算", error_code="AGENT_BUDGET_INVALID"
                    )
                cursor = await conn.execute(
                    "SELECT deadline_at > now() AS active FROM agent_workflow_run WHERE run_id=%s",
                    (run_id,),
                )
                if not (await cursor.fetchone())["active"]:
                    raise RunTimedOutError("Agent 运行期限已到")
                cursor = await conn.execute(
                    """SELECT * FROM agent_research_operation
                    WHERE run_id=%s AND operation_key=%s ORDER BY attempt DESC LIMIT 1""",
                    (run_id, key),
                )
                latest = await cursor.fetchone()
                if latest and (
                    latest["request_hash"] != request_hash
                    or latest["kind"] != kind
                    or latest["purpose"] != purpose
                ):
                    raise WorkflowExecutionError(
                        "操作键与原请求不一致", error_code="AGENT_REPLAY_MISMATCH"
                    )
                if latest and latest["status"] == "SETTLED":
                    return {"replay": latest["safe_result"], "attempt": latest["attempt"]}
                if latest and latest["status"] == "UNKNOWN" and kind == "MODEL":
                    usage = latest["actual_usage"] or {}
                    failure = usage.get("model_failure") if type(usage) is dict else None
                    if type(failure) is dict and failure.get("retryable") is False:
                        raise WorkflowExecutionError(
                            "模型操作不可重复请求", error_code="AGENT_MODEL_NOT_RETRYABLE"
                        )
                if latest and latest["status"] == "RESERVED":
                    if str(latest["claim_token"]) == claim_token:
                        raise WorkflowExecutionError(
                            "该操作仍在执行", error_code="AGENT_OPERATION_IN_PROGRESS"
                        )
                    await conn.execute(
                        (
                            "UPDATE agent_research_operation SET status='UNKNOWN',\n"
                            "                        claim_token=%s::uuid, settled_at=now() WHERE "
                            "run_id=%s AND operation_key=%s AND attempt=%s"
                        ),
                        (claim_token, run_id, key, latest["attempt"]),
                    )
                if latest and (kind == "TOOL" or latest["attempt"] >= 2):
                    ambiguous = True
                else:
                    totals = await self._totals(conn, run_id)
                    model_cap = min(stored.max_model_calls, budget.max_model_calls)
                    tool_cap = min(stored.max_tool_calls, budget.max_tool_calls)
                    decision_cap = min(stored.max_decision_steps, budget.max_decision_steps)
                    if (
                        (kind == "MODEL" and totals["model_calls"] >= model_cap)
                        or (kind == "TOOL" and totals["tool_calls"] >= tool_cap)
                        or (
                            purpose == "DECISION"
                            and not latest
                            and totals["decision_steps"] >= decision_cap
                        )
                        or totals["input_charged"] + input_reserved
                        > min(stored.max_input_tokens, budget.max_input_tokens)
                        or totals["output_charged"] + output_reserved
                        > min(stored.max_output_tokens, budget.max_output_tokens)
                    ):
                        raise RunBudgetExceededError("Agent 统一调用或 token 准入额度耗尽")
                    attempt = latest["attempt"] + 1 if latest else 1
                    await conn.execute(
                        """INSERT INTO agent_research_operation
                        (run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token)
                        VALUES (%s,%s,%s,%s,%s,%s,'RESERVED',%s,%s,%s::uuid)""",
                        (
                            run_id,
                            key,
                            attempt,
                            kind,
                            purpose,
                            request_hash,
                            input_reserved,
                            output_reserved,
                            claim_token,
                        ),
                    )
                    return {"replay": None, "attempt": attempt}
        if ambiguous:
            raise WorkflowExecutionError(
                "上次操作结果未知\uff0c需对账\uff0c禁止重复外部调用",
                error_code="AGENT_OPERATION_UNKNOWN",
            )

    async def settle(self, run_id, claim_token, key, attempt, value, usage, *, unknown=False):
        if len(canonical(value).encode()) > 120000:
            raise WorkflowExecutionError("操作结果过大", error_code="AGENT_RESULT_TOO_LARGE")
        async with self.repository.pool.connection() as conn:
            async with conn.transaction():
                await self.repository._lock_active_budget_run(conn, run_id, claim_token)
                cursor = await conn.execute(
                    (
                        "SELECT * FROM agent_research_operation WHERE run_id=%s AND "
                        "operation_key=%s AND attempt=%s"
                    ),
                    (run_id, key, attempt),
                )
                row = await cursor.fetchone()
                if (
                    row
                    and row["status"] == "SETTLED"
                    and not unknown
                    and str(row["claim_token"]) == claim_token
                ):
                    if canonical(row["safe_result"]) == canonical(value) and canonical(
                        row["actual_usage"]
                    ) == canonical(usage):
                        # Java settles reads/publications with the authoritative record.
                        return
                if not row or row["status"] != "RESERVED" or str(row["claim_token"]) != claim_token:
                    raise StaleClaimError("Agent 预留已失效")
                await conn.execute(
                    (
                        "UPDATE agent_research_operation SET status=%s, safe_result=%s,\n"
                        "                    actual_usage=%s, settled_at=now() WHERE run_id=%s AND "
                        "operation_key=%s AND attempt=%s"
                    ),
                    (
                        "UNKNOWN" if unknown else "SETTLED",
                        None if unknown else Jsonb(value),
                        Jsonb(usage) if not unknown or usage else None,
                        run_id,
                        key,
                        attempt,
                    ),
                )

    @staticmethod
    async def _totals(conn, run_id):
        cursor = await conn.execute(
            (
                "SELECT count(*) FILTER (WHERE kind='MODEL') AS model_calls,\n"
                "            count(*) FILTER (WHERE kind='TOOL') AS tool_calls,\n"
                "            count(DISTINCT operation_key) FILTER (WHERE purpose='DECISION') AS "
                "decision_steps,\n"
                "            COALESCE(sum(COALESCE((actual_usage->>'input_tokens')::bigint,"
                "input_reserved)),0) AS input_charged,\n"
                "            COALESCE(sum(COALESCE((actual_usage->>'output_tokens')::bigint,"
                "output_reserved)),0) AS output_charged,\n"
                "            count(*) FILTER (WHERE kind='MODEL' AND actual_usage->>'input_tokens' "
                "IS NULL) AS input_unknown,\n"
                "            count(*) FILTER (WHERE kind='MODEL' AND "
                "actual_usage->>'output_tokens' IS NULL) AS output_unknown,\n"
                "            COALESCE(sum((actual_usage->>'input_tokens')::bigint),0) AS "
                "input_measured,\n"
                "            COALESCE(sum((actual_usage->>'output_tokens')::bigint),0) AS "
                "output_measured\n"
                "            FROM agent_research_operation WHERE run_id=%s"
            ),
            (run_id,),
        )
        return dict(await cursor.fetchone())

    async def summary(self, run_id, claim_token):
        async with self.repository.pool.connection() as conn:
            await self.repository._lock_active_budget_run(conn, run_id, claim_token)
            data = await self._totals(conn, run_id)
        return {
            "modelCalls": data["model_calls"],
            "toolCalls": data["tool_calls"],
            # PostgreSQL SUM(bigint) is numeric: psycopg returns Decimal. Keep
            # persisted usage JSON-native before the next model request is encoded.
            "inputTokens": None if data["input_unknown"] else int(data["input_measured"]),
            "outputTokens": None if data["output_unknown"] else int(data["output_measured"]),
            "inputTokensStatus": "unknown" if data["input_unknown"] else "known",
            "outputTokensStatus": "unknown" if data["output_unknown"] else "known",
            "inputAdmissionTokens": int(data["input_charged"]),
            "outputAdmissionTokens": int(data["output_charged"]),
            "estimatedCost": None,
            "costStatus": "unknown",
            "currency": "CNY",
        }

    async def save_tasks(self, run_id, claim_token, tasks):
        from .agent_completion import ensure_criteria

        ensure_criteria(run_id, tasks)
        async with self.repository.pool.connection() as conn:
            async with conn.transaction():
                await self.repository._lock_active_budget_run(conn, run_id, claim_token)
                for task in tasks:
                    cursor = await conn.execute(
                        (
                            "INSERT INTO agent_research_task\n"
                            "                        (run_id,task_id,objective,dependencies,status,"
                            "acceptance_criteria,evidence_ids,plan_version,task_json,claim_token)\n"
                            "                        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::uuid)\n"
                            "                        ON CONFLICT (run_id,task_id) DO UPDATE SET "
                            "status=EXCLUDED.status,\n"
                            "                        evidence_ids=EXCLUDED.evidence_ids,"
                            "task_json=EXCLUDED.task_json,claim_token=EXCLUDED.claim_token,\n"
                            "                        updated_at=now() WHERE "
                            "agent_research_task.plan_version=EXCLUDED.plan_version\n"
                            "                        AND "
                            "agent_research_task.objective=EXCLUDED.objective\n"
                            "                        AND "
                            "agent_research_task.dependencies=EXCLUDED.dependencies\n"
                            "                        AND "
                            "agent_research_task.acceptance_criteria=EXCLUDED.acceptance_criteria"
                        ),
                        (
                            run_id,
                            task["task_id"],
                            task["objective"],
                            task["dependencies"],
                            task["status"],
                            task["acceptance_criteria"],
                            task["evidence_ids"],
                            task["plan_version"],
                            Jsonb(task),
                            claim_token,
                        ),
                    )
                    if cursor.rowcount != 1:
                        raise WorkflowExecutionError(
                            "任务目标或版本不能静默改写", error_code="AGENT_TASK_MISMATCH"
                        )
                    for criterion in task["criteria"]:
                        await conn.execute(
                            """INSERT INTO agent_research_criterion
                            (run_id,task_id,criterion_id,criterion_index,criterion_text,claim_token)
                            VALUES (%s,%s,%s,%s,%s,%s::uuid)
                            ON CONFLICT (run_id,task_id,criterion_id) DO NOTHING""",
                            (
                                run_id,
                                task["task_id"],
                                criterion["criterion_id"],
                                criterion["index"],
                                criterion["text"],
                                claim_token,
                            ),
                        )

    async def save_requirements(
        self, run_id, claim_token, manifest, bindings, tasks, declaration_key
    ):
        """CAS immutable manifest and append-only native criterion associations.

        Replay after persistence/before checkpoint accepts the same immutable model
        response and never changes the next prepared request using fresh DB data.
        """
        from .agent_requirements import bind_requirements, validate_manifest

        manifest = validate_manifest(manifest, run_id=run_id)
        bindings = bind_requirements(manifest, tasks, bindings)
        task_for = {c["criterion_id"]: t["task_id"] for t in tasks for c in t["criteria"]}
        async with self.repository.pool.connection() as conn:
            async with conn.transaction():
                await self.repository._lock_active_budget_run(conn, run_id, claim_token)
                cursor = await conn.execute(
                    "SELECT question FROM agent_workflow_run WHERE run_id=%s", (run_id,)
                )
                validate_manifest(
                    manifest, run_id=run_id, question=(await cursor.fetchone())["question"]
                )
                cursor = await conn.execute(
                    "SELECT manifest FROM agent_research_requirements WHERE run_id=%s", (run_id,)
                )
                saved = await cursor.fetchone()
                if saved is None:
                    cursor = await conn.execute(
                        "SELECT attempt,safe_result FROM agent_research_operation WHERE run_id=%s "
                        "AND operation_key=%s "
                        "AND status='SETTLED' AND kind='MODEL' AND purpose='DECISION' "
                        "ORDER BY attempt DESC LIMIT 1",
                        (run_id, declaration_key),
                    )
                    receipt = await cursor.fetchone()
                    if receipt is None:
                        raise WorkflowExecutionError(
                            "Original requirements lack a settled planning receipt",
                            error_code="REQUIREMENT_DECLARATION_MISSING",
                        )
                    from .agent_question_segments import declaration_drafts
                    from .agent_requirements import freeze_requirements

                    # Bind canonical storage to the actual settled declaration, including
                    # v2 server mapping provenance. Legacy declarations keep their meaning.
                    cursor = await conn.execute(
                        "SELECT question FROM agent_workflow_run WHERE run_id=%s", (run_id,)
                    )
                    question = (await cursor.fetchone())["question"]
                    freeze_requirements(run_id, question,
                                        declaration_drafts(question, receipt["safe_result"]),
                                        existing=manifest)
                    await conn.execute(
                        "INSERT INTO "
                        "agent_research_requirements(run_id,manifest,declaration_key,"
                        "declaration_attempt,claim_token) "
                        "VALUES (%s,%s,%s,%s,%s::uuid)",
                        (run_id, Jsonb(manifest), declaration_key, receipt["attempt"], claim_token),
                    )
                elif canonical(saved["manifest"]) != canonical(manifest):
                    raise WorkflowExecutionError(
                        "Original requirements cannot change", error_code="REQUIREMENTS_CHANGED"
                    )
                for row in bindings:
                    cursor = await conn.execute(
                        "INSERT INTO "
                        "agent_research_requirement_binding(run_id,requirement_id,task_id,"
                        "criterion_id,claim_token) "
                        "VALUES (%s,%s,%s,%s,%s::uuid) ON CONFLICT "
                        "(run_id,requirement_id) DO NOTHING",
                        (
                            run_id,
                            row["requirement_id"],
                            task_for[row["criterion_id"]],
                            row["criterion_id"],
                            claim_token,
                        ),
                    )
                cursor = await conn.execute(
                    "SELECT requirement_id,criterion_id FROM "
                    "agent_research_requirement_binding WHERE run_id=%s ORDER BY requirement_id",
                    (run_id,),
                )
                stored = [dict(row) for row in await cursor.fetchall()]
                if canonical(stored) != canonical(bindings):
                    raise WorkflowExecutionError(
                        "Requirement associations cannot change or disappear",
                        error_code="REQUIREMENT_BINDING_CHANGED",
                    )

    async def begin_check(self, run_id, claim_token, task, selected, entry, key):
        from .agent_completion import server_identity

        identity = server_identity(entry["claim_specs"])
        async with self.repository.pool.connection() as conn:
            async with conn.transaction():
                await self.repository._lock_active_budget_run(conn, run_id, claim_token)
                await conn.execute(
                    """INSERT INTO agent_research_investigation_progress
                    (run_id,investigation,current_call_id,claim_token) VALUES (%s,%s,%s,%s::uuid)
                    ON CONFLICT (run_id,investigation) DO UPDATE
                    SET current_call_id=EXCLUDED.current_call_id,
                        claim_token=EXCLUDED.claim_token""",
                    (run_id, identity, key, claim_token),
                )
            # Keep the pending attempt durable even if a coverage write is rejected.
            async with conn.transaction():
                await self.repository._lock_active_budget_run(conn, run_id, claim_token)
                cursor = await conn.execute(
                    "SELECT agent_task_dependency_snapshot(%s,%s) AS snapshot",
                    (run_id, task["task_id"]),
                )
                snapshot = (await cursor.fetchone())["snapshot"]
                for criterion in task["criteria"]:
                    if criterion["criterion_id"] not in selected:
                        continue
                    expected = criterion["expected_claim"]
                    cursor = await conn.execute(
                        """UPDATE agent_research_criterion SET expected_claim=%s,expected_hash=%s,
                        investigation=%s,last_call_id=%s,dependency_snapshot=%s,claim_token=%s::uuid
                        WHERE run_id=%s AND task_id=%s AND criterion_id=%s""",
                        (
                            Jsonb(expected),
                            hashlib.sha256(canonical(expected).encode()).hexdigest(),
                            identity,
                            key,
                            Jsonb(snapshot),
                            claim_token,
                            run_id,
                            task["task_id"],
                            criterion["criterion_id"],
                        ),
                    )
                    if cursor.rowcount != 1:
                        raise WorkflowExecutionError(
                            "完成标准缺少原生绑定", error_code="AGENT_CRITERION_MISSING"
                        )

    async def scope(self, run_id, claim_token):
        async with self.repository.pool.connection() as conn:
            await self.repository._lock_active_budget_run(conn, run_id, claim_token)
            cursor = await conn.execute(
                "SELECT project_id,tenant_id,owner_id FROM agent_research_run WHERE run_id=%s",
                (run_id,),
            )
            row = await cursor.fetchone()
            if row is None:
                raise WorkflowExecutionError(
                    "Agent 缺少服务端项目身份", error_code="AGENT_SCOPE_MISSING"
                )
            return dict(row)


class AgentBudgetGateway:
    def __init__(self, *, run_id, claim_token, budget, ledger, model: AgentModel, guard):
        self.run_id, self.claim_token, self.budget = run_id, claim_token, budget
        self.ledger, self.model, self.guard = ledger, model, guard

    @staticmethod
    def classify_model_failure(
        key, attempt, error, result, schema_name, *, request=None, request_hash=None,
    ):
        failure_kind, error_class, retryable, status_code, paths = (
            "SCHEMA", "validator_rejected", False, None, [],
        )
        tool_call_count = None
        schema_diagnostic = None
        diagnostic_enabled = (
            request is not None
            and request.request_binding.get("instruction_policy")
            in SUPPORTED_POLICIES
            and request_hash is not None
        )
        stage, domain_code = None, None
        if isinstance(error, ResultValidationError):
            stage, error = error.stage, error.error
        if isinstance(error, AgentModelFailure):
            failure_kind, error_class, retryable = (
                error.failure_kind, error.error_class, error.retryable,
            )
            status_code, tool_call_count = error.status_code, error.tool_call_count
            input_tokens, output_tokens = error.input_tokens, error.output_tokens
        else:
            input_tokens = result.input_tokens if result is not None else None
            output_tokens = result.output_tokens if result is not None else None
            if isinstance(error, JsonSchemaValidationError):
                error_class = "schema_validation"
                paths = [safe_issue_path(error.absolute_path)]
                if diagnostic_enabled:
                    schema_diagnostic = schema_failure_diagnostic(
                        error, request, request_hash, SAFE_FIELDS,
                    )
            elif isinstance(error, PydanticValidationError):
                error_class = "schema_validation"
                paths = sorted({safe_issue_path(item["loc"]) for item in error.errors(
                    include_input=False, include_context=False, include_url=False)})[:4]
                if diagnostic_enabled:
                    schema_diagnostic = schema_failure_diagnostic(
                        error, request, request_hash, SAFE_FIELDS, pydantic=True,
                    )
            elif isinstance(error, RequirementError):
                domain_code = safe_requirement_code(error.code)
                error_class = "requirement_validation"
                # Unknown codes remain opaque application defects, not domain rejections.
                if domain_code is None:
                    failure_kind, error_class = "INTERNAL", "application_internal"
            elif isinstance(error, EvidenceCheckError):
                code = error.args[0] if len(error.args) == 1 else None
                domain_code = safe_check_code(code)
                if domain_code is None:
                    failure_kind, error_class = "INTERNAL", "application_internal"
            elif not isinstance(error, WorkflowExecutionError):
                failure_kind, error_class = "INTERNAL", "application_internal"
        failure = ModelCallError(
            key, failure_kind=failure_kind, attempt=attempt,
            error_class=error_class, retryable=retryable,
            validation_stage=stage, domain_error_code=domain_code,
            json_diagnostic=error.json_diagnostic if isinstance(error, AgentModelFailure) else None,
        )
        failure.status_code = status_code
        failure.validation_issue_codes = paths
        failure.tool_call_count = tool_call_count
        failure.schema_name = schema_name
        metadata = {
            "failure_kind": failure_kind,
            "error_class": error_class,
            "retryable": retryable,
        }
        if failure.validation_stage is not None:
            metadata["validation_stage"] = failure.validation_stage
        if failure.domain_error_code is not None:
            metadata["domain_error_code"] = failure.domain_error_code
        if isinstance(error, RequirementError) and domain_code is not None:
            diagnostic = safe_segment_diagnostic(error.segment_diagnostic)
            if diagnostic is not None:
                metadata["question_segments"] = diagnostic
        if isinstance(error, AgentModelFailure) and error.identity_diagnostic is not None:
            metadata["identity"] = error.identity_diagnostic
        if failure.json_diagnostic is not None:
            metadata["json_diagnostic"] = failure.json_diagnostic
        if (isinstance(error, AgentModelFailure) and type(error.finish_reason) is str
                and error.finish_reason in FINISH_REASONS):
            metadata["finish_reason"] = error.finish_reason
            failure.finish_reason = error.finish_reason
        if status_code is not None:
            metadata["status_code"] = status_code
        if paths:
            metadata["validation_issue_codes"] = paths
        if schema_diagnostic is not None:
            metadata["schema_diagnostic"] = schema_diagnostic
        if tool_call_count is not None:
            metadata["tool_call_count"] = tool_call_count
        usage = {"model_failure": metadata}
        if type(input_tokens) is int and 0 <= input_tokens <= 2**63 - 1:
            usage["input_tokens"] = input_tokens
        if type(output_tokens) is int and 0 <= output_tokens <= 2**63 - 1:
            usage["output_tokens"] = output_tokens
        return failure, usage

    @staticmethod
    def validate_result(request, result, validate):
        # Remote refs cannot cause a network fetch or grant model-supplied authority.
        schema_check = Draft202012Validator(
            request.result_schema,
            registry=Registry(
                retrieve=lambda uri: (_ for _ in ()).throw(ValueError("remote schema forbidden"))
            ),
        )
        try:
            schema_check.validate(result.value)
        except Exception as error:
            raise ResultValidationError(error, "result_schema") from None
        if validate is not None:
            try:
                validate(result.value)
            except (RunBudgetExceededError, RunCancelledError, RunTimedOutError, StaleClaimError):
                raise
            except ResultValidationError:
                raise
            except Exception as error:
                raise ResultValidationError(error, "domain_validation") from None

    async def model_call(
        self, key, purpose, request: ModelRequest, validate: Callable | None = None,
        *, canonicalize: Callable | None = None,
    ):
        # Freeze the exact provider bytes before admission; binding metadata is not sent.
        prepared = None
        try:
            # model_copy(update=...) bypasses DTO validation. Enforce purpose here
            # before encoding, reservation or any provider invocation.
            ModelRequest.model_validate(request.model_dump(by_alias=True))
            policy = request.request_binding.get("instruction_policy")
            if policy is not None and policy not in SUPPORTED_POLICIES:
                raise ValueError("Unknown instruction policy")
            if request.max_output_tokens > 1024 and purpose != "CHECK":
                raise ValueError("Expanded output is CHECK-only")
            request = request.model_copy(deep=True)
            if isinstance(self.model, OpenAIAgentModel):
                prepared = self.model.prepare(request)
                encoded_request = prepared.identity
                input_reserved = len(prepared.wire) + 1024
            else:
                request_data = {**request.model_dump(mode="json", by_alias=True),
                                "rules": MODEL_RULES, "transport": "fixture-invoke/1"}
                encoded_request = canonical(request_data).encode()
                input_reserved = len(encoded_request) + 1024
        except Exception as error:
            label = (
                error.error_class if isinstance(error, AgentModelFailure) else "request_encoding"
            )
            failure = ModelCallError(
                key, attempt=0, failure_kind="SCHEMA",
                error_class=label, retryable=False,
            )
            raise failure from None
        request_hash = hashlib.sha256(encoded_request).hexdigest()
        for _ in range(2):
            await self.guard()
            try:
                reservation = await self.ledger.reserve(
                    self.run_id,
                    self.claim_token,
                    key,
                    "MODEL",
                    purpose,
                    request_hash,
                    input_reserved,
                    request.max_output_tokens,
                    self.budget,
                )
            except BudgetClaimConflictError:
                raise StaleClaimError("Agent claim 已变化") from None
            if reservation["replay"] is not None:
                result = ModelResult.model_validate(reservation["replay"])
                self.check_bounds(result, input_reserved, request.max_output_tokens)
                try:
                    if request.request_binding.get("planner_contract") in {
                            PLANNER_VERSION, "agent-planning-obligations/3"}:
                        if (any(result.request_binding.get(k) != v
                                for k, v in request.request_binding.items())
                                or result.request_binding.get("response_sha256")
                                != hashlib.sha256(canonical(result.value).encode()).hexdigest()):
                            raise ResultValidationError(
                                RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID"),
                                "planning_requirements",
                            )
                        try:
                            wire_value = replay_declaration(result.model_dump(mode="json"))
                        except Exception as error:
                            raise ResultValidationError(error, "planning_requirements") from None
                        wire_result = result.model_copy(update={"value": wire_value})
                        self.validate_result(request, wire_result, validate)
                        try:
                            reconstructed = canonicalize(wire_value) if canonicalize else None
                        except Exception as error:
                            raise ResultValidationError(error, "planning_requirements") from None
                        if (canonicalize is None
                                or canonical(reconstructed) != canonical(result.value)):
                            raise ResultValidationError(
                                RequirementError("REQUIREMENT_SEGMENT_BINDING_INVALID"),
                                "planning_requirements",
                            )
                        return wire_result
                    self.validate_result(request, result, validate)
                except (
                    RunBudgetExceededError, RunCancelledError, RunTimedOutError, StaleClaimError,
                ):
                    raise
                except Exception as error:
                    failure, _ = self.classify_model_failure(
                        key, reservation["attempt"], error, result, request.name,
                        request=request, request_hash=request_hash,
                    )
                    raise failure from None
                return result
            result = None
            try:
                result = (
                    await self.model.invoke_prepared(request, prepared) if prepared is not None
                    else await self.model.invoke(request)
                )
                self.validate_result(request, result, validate)
                stored_result = result
                extra_binding = {}
                if canonicalize is not None:
                    declaration = canonical(result.value)
                    if len(declaration.encode("utf-8")) > 65536:
                        raise ResultValidationError(
                            RequirementError("REQUIREMENT_DECLARATION_LIMIT"),
                            "planning_requirements",
                        )
                    try:
                        value = canonicalize(result.value)
                    except Exception as error:
                        raise ResultValidationError(error, "planning_requirements") from None
                    stored_result = result.model_copy(update={"value": value})
                    extra_binding = {
                        "planner_declaration": declaration,
                        "wire_response_sha256": hashlib.sha256(declaration.encode()).hexdigest(),
                    }
                result = result.model_copy(
                    update={
                        "request_binding": {
                            **request.request_binding,
                            **extra_binding,
                            "response_sha256": hashlib.sha256(
                                canonical(stored_result.value).encode()
                            ).hexdigest(),
                        }
                    }
                )
                stored_result = stored_result.model_copy(
                    update={"request_binding": result.request_binding}
                )
                envelope_bytes = len(canonical(stored_result.model_dump(mode="json")).encode())
                if canonicalize is not None and envelope_bytes > 120000:
                    raise ResultValidationError(
                        RequirementError("REQUIREMENT_DECLARATION_LIMIT"), "planning_requirements"
                    )
            except (RunBudgetExceededError, RunCancelledError, RunTimedOutError, StaleClaimError):
                raise
            except Exception as error:
                failure, usage = self.classify_model_failure(
                    key, reservation["attempt"], error, result, request.name,
                    request=request, request_hash=request_hash,
                )
                try:
                    await self.ledger.settle(
                        self.run_id, self.claim_token, key, reservation["attempt"],
                        {}, usage, unknown=True,
                    )
                except StaleClaimError:
                    raise
                except Exception:
                    raise WorkflowExecutionError(
                        "Agent 模型回执结算失败", error_code="AGENT_SETTLEMENT_FAILED"
                    ) from None
                if failure.retryable and reservation["attempt"] < 2:
                    continue
                raise failure from None
            await self.guard()
            try:
                await self.ledger.settle(
                    self.run_id, self.claim_token, key, reservation["attempt"],
                    stored_result.model_dump(mode="json"),
                    (result.model_dump(mode="json", exclude={"value"}) if canonicalize is None
                     else {**result.model_dump(mode="json", exclude={"value", "request_binding"}),
                           "request_binding": {k: v for k, v in result.request_binding.items()
                                               if k != "planner_declaration"}}),
                )
            except StaleClaimError:
                raise
            except Exception:
                raise WorkflowExecutionError(
                    "Agent 模型回执结算失败", error_code="AGENT_SETTLEMENT_FAILED"
                ) from None
            self.check_bounds(result, input_reserved, request.max_output_tokens)
            return result
        raise AssertionError("bounded model admission loop exited without a result")

    async def tool_call(
        self, key, purpose, payload, invoke: Callable[[], Awaitable[dict[str, Any]]]
    ):
        await self.guard()
        reservation = await self.ledger.reserve(
            self.run_id,
            self.claim_token,
            key,
            "TOOL",
            purpose,
            hashlib.sha256(canonical(payload).encode()).hexdigest(),
            0,
            0,
            self.budget,
        )
        if reservation["replay"] is not None:
            return reservation["replay"]
        try:
            result = await invoke()
            await self.guard()
            await self.ledger.settle(
                self.run_id, self.claim_token, key, reservation["attempt"], result, {}
            )
            return result
        except (StaleClaimError, RunBudgetExceededError, RunCancelledError, RunTimedOutError):
            raise
        except Exception:
            await self.ledger.settle(
                self.run_id, self.claim_token, key, reservation["attempt"], {}, {}, unknown=True
            )
            raise WorkflowExecutionError(
                "外部操作结果未知\uff0c需对账后继续", error_code="AGENT_OPERATION_UNKNOWN"
            ) from None

    @staticmethod
    def check_bounds(result, input_reserved, output_reserved):
        if (result.input_tokens is not None and result.input_tokens > input_reserved) or (
            result.output_tokens is not None and result.output_tokens > output_reserved
        ):
            raise RunBudgetExceededError("Provider 用量超出预留边界\uff0c停止后续调用")
