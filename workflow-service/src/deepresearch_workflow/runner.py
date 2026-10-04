from __future__ import annotations

import asyncio
import hashlib
import logging
import time
import traceback
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

from .control_plane import FinalizeRejectedError
from .domain import (
    AgentRunBudget,
    ClaimedRun,
    EventRecord,
    FinalizeRequest,
    RunBudget,
    UsageDelta,
    WorkflowStage,
    WorkflowState,
    WorkflowStatus,
)
from .graph import (
    RunBudgetExceededError,
    RunCancelledError,
    RunTimedOutError,
    StaleClaimError,
    WorkflowExecutionError,
)
from .ports import BudgetClaimConflictError, ControlPlaneClient, WorkflowRepository
from .settings import Settings

# Uvicorn owns the container logging handlers. Using its child namespace keeps
# application lifecycle records visible without adding a second handler.
logger = logging.getLogger("uvicorn.error.deepresearch_workflow.runner")


class WorkflowRunner:
    """Claims rows, owns their session lock, drives checkpoints, then asks Java to finalize."""

    def __init__(
        self,
        *,
        repository: WorkflowRepository,
        control_plane: ControlPlaneClient,
        graph_factory: Callable[[str, RunBudget], Any],
        settings: Settings,
        agent_ledger_factory: Callable | None = None,
    ) -> None:
        self._repository = repository
        self._control_plane = control_plane
        self._graph_factory = graph_factory
        self._settings = settings
        self._agent_ledger_factory = agent_ledger_factory
        self._stop = asyncio.Event()
        self.active_runs = 0
        self.last_error_code: str | None = None

    def stop(self) -> None:
        self._stop.set()

    async def serve_forever(self) -> None:
        tasks: set[asyncio.Task[None]] = set()
        try:
            while not self._stop.is_set():
                finished = {task for task in tasks if task.done()}
                for task in finished:
                    tasks.remove(task)
                    try:
                        task.result()
                    except asyncio.CancelledError:
                        pass
                    except Exception as failure:
                        run_id = task.get_name().removeprefix("workflow:")
                        self._log_failure(run_id, failure)

                while len(tasks) < self._settings.max_concurrent_runs:
                    claimed = await self._repository.claim_next(
                        self._settings.instance_id, self._settings.lease_seconds
                    )
                    if claimed is None:
                        break
                    task = asyncio.create_task(
                        self.run_claimed(claimed), name=f"workflow:{claimed.run_id}"
                    )
                    tasks.add(task)

                if tasks:
                    await asyncio.wait(
                        tasks,
                        timeout=self._settings.poll_interval_seconds,
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                else:
                    try:
                        await asyncio.wait_for(
                            self._stop.wait(), timeout=self._settings.poll_interval_seconds
                        )
                    except TimeoutError:
                        pass
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    async def run_claimed(self, run: ClaimedRun) -> None:
        self.active_runs += 1
        started = time.monotonic()
        logger.info(
            "workflow execution started run_id=%s",
            self._safe_log_scalar(run.run_id),
        )
        heartbeat = asyncio.create_task(self._heartbeat(run), name=f"heartbeat:{run.run_id}")
        try:
            try:
                result = await self._await_or_claim_loss(
                    self._execute(run), heartbeat, name=f"graph:{run.run_id}"
                )
            except (StaleClaimError, FinalizeRejectedError):
                self.last_error_code = "STALE_CLAIM"
                return
            except asyncio.CancelledError:
                raise
            except Exception as failure:
                self._log_failure(run.run_id, failure)
                try:
                    await self._await_or_claim_loss(
                        self._finalize_failure(run, failure, started),
                        heartbeat,
                        name=f"failure-finalize:{run.run_id}",
                    )
                except (StaleClaimError, FinalizeRejectedError):
                    self.last_error_code = "STALE_CLAIM"
                except httpx.HTTPError as failure:
                    self.last_error_code = "FINALIZE_UNAVAILABLE"
                    self._log_finalize_unavailable(run.run_id, "failure", failure)
                return

            try:
                await self._await_or_claim_loss(
                    self._finalize_result(run, result, started),
                    heartbeat,
                    name=f"result-finalize:{run.run_id}",
                )
                logger.info(
                    "workflow execution finalized run_id=%s status=%s duration_ms=%s",
                    self._safe_log_scalar(run.run_id),
                    self._safe_log_scalar(result.get("final_status", "UNKNOWN")),
                    max(0, int((time.monotonic() - started) * 1000)),
                )
            except (StaleClaimError, FinalizeRejectedError):
                self.last_error_code = "STALE_CLAIM"
            except httpx.HTTPError as failure:
                # Keep FINALIZING. A later claimant resumes the completed checkpoint and
                # retries the same idempotent Java finalize request.
                self.last_error_code = "FINALIZE_UNAVAILABLE"
                self._log_finalize_unavailable(run.run_id, "result", failure)
        except (StaleClaimError, FinalizeRejectedError):
            self.last_error_code = "STALE_CLAIM"
        except asyncio.CancelledError:
            raise
        except Exception as failure:
            self.last_error_code = "RUNNER_INTERNAL_ERROR"
            self._log_failure(run.run_id, failure)
        finally:
            heartbeat.cancel()
            await asyncio.gather(heartbeat, return_exceptions=True)
            self.active_runs -= 1

    async def _execute(self, run: ClaimedRun) -> WorkflowState:
        async with self._repository.run_lock(run.run_id):
            active, cancelled = await self._repository.assert_active_claim(
                run.run_id, run.claim_token
            )
            if not active:
                raise StaleClaimError("claim changed before acquiring the run lock")
            if cancelled:
                raise RunCancelledError("run was cancelled before execution")

            budget = self._effective_budget(run.budget)
            graph = self._graph_factory(run.claim_token, budget)
            config: dict[str, Any] = {
                "configurable": {"thread_id": run.graph_thread_id},
                "recursion_limit": 32,
                "max_concurrency": budget.max_concurrency,
            }
            snapshot = await graph.aget_state(config)
            graph_input: WorkflowState | None
            if snapshot.values:
                claim_epoch = hashlib.sha256(run.claim_token.encode()).hexdigest()[:12]
                await self._repository.write_event(
                    EventRecord(
                        run_id=run.run_id,
                        event_key=f"runner:resumed:{claim_epoch}",
                        role="SYSTEM",
                        event_type="RUN_RESUMED",
                        safe_payload={
                            "checkpointPending": bool(snapshot.next),
                            "previousStatus": run.status.value,
                        },
                    ),
                    run.claim_token,
                )
                if snapshot.next:
                    graph_input = None
                else:
                    # A Java finalize response may have been lost. Completed graph state is
                    # valid after the research deadline and must be finalized idempotently.
                    return WorkflowState(**dict(snapshot.values))
            else:
                graph_input = self._initial_state(run)

            remaining = min(
                (run.deadline_at - datetime.now(UTC)).total_seconds(),
                float(budget.deadline_seconds),
            )
            if remaining <= 0:
                raise RunTimedOutError("workflow deadline elapsed")
            try:
                async with asyncio.timeout(remaining):
                    result = await graph.ainvoke(
                        graph_input,
                        config,
                        durability="sync",
                    )
                    return WorkflowState(**dict(result))
            except TimeoutError as failure:
                raise RunTimedOutError("workflow deadline elapsed") from failure

    def _effective_budget(self, snapshot: RunBudget) -> RunBudget:
        """Apply process safety ceilings without replacing the persisted snapshot."""

        if isinstance(snapshot,AgentRunBudget):
            return snapshot.model_copy(
                update={
                    "max_model_calls": min(
                        snapshot.max_model_calls, self._settings.max_model_calls
                    ),
                    "max_tool_calls": min(
                        snapshot.max_tool_calls, self._settings.max_tool_calls
                    ),
                }
            )
        return RunBudget(
            max_tasks=min(snapshot.max_tasks, self._settings.max_tasks),
            max_concurrency=min(
                snapshot.max_concurrency,
                self._settings.worker_max_concurrency,
            ),
            max_revision_rounds=min(
                snapshot.max_revision_rounds,
                self._settings.max_revision_rounds,
            ),
            max_model_calls=min(snapshot.max_model_calls, self._settings.max_model_calls),
            max_tool_calls=min(snapshot.max_tool_calls, self._settings.max_tool_calls),
            max_tokens=min(snapshot.max_tokens, self._settings.max_tokens),
            max_cost_cny=min(snapshot.max_cost_cny, self._settings.max_cost_cny),
            deadline_seconds=min(
                snapshot.deadline_seconds,
                self._settings.default_timeout_seconds,
            ),
        )

    async def _await_or_claim_loss(
        self,
        operation,
        heartbeat: asyncio.Task[Exception],
        *,
        name: str,
    ):
        # Race the operation against fencing renewal. A lost claim cancels and
        # drains the old claimant immediately; repository writes still enforce
        # the claim token if the operation happened to finish first.
        task = asyncio.create_task(operation, name=name)
        try:
            done, _ = await asyncio.wait({task, heartbeat}, return_when=asyncio.FIRST_COMPLETED)
            if heartbeat in done and task not in done:
                failure = heartbeat.result()
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                raise failure
            return await task
        except asyncio.CancelledError:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            raise

    async def _heartbeat(self, run: ClaimedRun) -> Exception:
        while True:
            await asyncio.sleep(self._settings.heartbeat_seconds)
            renewed = await self._repository.heartbeat(
                run.run_id,
                run.claim_token,
                self._settings.instance_id,
                self._settings.lease_seconds,
            )
            if not renewed:
                return StaleClaimError("workflow heartbeat lost its fencing token")

    async def _finalize_result(self, run: ClaimedRun, state: WorkflowState, started: float) -> None:
        status = WorkflowStatus(
            state.get("final_status", WorkflowStatus.INSUFFICIENT_EVIDENCE.value)
        )
        if status not in {
            WorkflowStatus.SUCCEEDED,
            WorkflowStatus.INSUFFICIENT_EVIDENCE,
            WorkflowStatus.FAILED,
        }:
            raise WorkflowExecutionError("graph returned an unsupported terminal status")
        usage = await self._reconcile_usage(
            run.run_id,
            run.claim_token,
            UsageDelta.model_validate(state.get("usage", {})),
        )
        await self._mark_finalizing(run, usage)
        await self._control_plane.finalize(
            run.run_id,
            FinalizeRequest(
                claimToken=run.claim_token,
                status=status,
                answer=state.get("final_answer") or None,
                citations=state.get("citations", []),
                usage=await self._result_wire_usage(run, usage, started),
                errorCode=state.get("error_code"),
                errorMessage=state.get("error_message"),
            ),
        )
        self.last_error_code = None

    async def _finalize_failure(self, run: ClaimedRun, failure: Exception, started: float) -> None:
        status, error_code = self._failure_status(failure)
        self.last_error_code = error_code
        active, cancelled = await self._repository.assert_active_claim(run.run_id, run.claim_token)
        if not active or cancelled:
            return
        usage = await self._reconcile_usage(
            run.run_id,
            run.claim_token,
            await self._repository.current_usage(run.run_id, run.claim_token),
        )
        try:
            await self._mark_finalizing(run, usage)
            await self._control_plane.finalize(
                run.run_id,
                FinalizeRequest(
                    claimToken=run.claim_token,
                    status=status,
                    usage=await self._result_wire_usage(run, usage, started),
                    errorCode=error_code,
                    errorMessage=self._safe_failure_message(failure),
                ),
            )
        except (FinalizeRejectedError, StaleClaimError):
            self.last_error_code = "STALE_CLAIM"

    async def _mark_finalizing(self, run: ClaimedRun, usage: UsageDelta) -> None:
        active, cancelled = await self._repository.assert_active_claim(run.run_id, run.claim_token)
        if not active or cancelled:
            raise StaleClaimError("claim changed before finalization")
        changed = await self._repository.update_progress(
            run.run_id,
            run.claim_token,
            status=WorkflowStatus.FINALIZING.value,
            stage=WorkflowStage.FINALIZING.value,
            usage=usage,
        )
        if not changed:
            raise StaleClaimError("claim changed while entering FINALIZING")

    async def _reconcile_usage(
        self,
        run_id: str,
        claim_token: str,
        usage: UsageDelta,
    ) -> UsageDelta:
        try:
            return await self._repository.reconcile_call_usage(run_id, claim_token, usage)
        except BudgetClaimConflictError as failure:
            raise StaleClaimError(str(failure)) from failure

    @staticmethod
    def _initial_state(run: ClaimedRun) -> WorkflowState:
        return WorkflowState(
            run_id=run.run_id,
            graph_thread_id=run.graph_thread_id,
            grant_id=run.grant_id,
            user_id=run.user_id,
            question=run.question,
            context_snapshot=run.context_snapshot,
            requested_scopes=run.requested_scopes,
            deadline_at=run.deadline_at.isoformat(),
            status=run.status.value,
            stage=run.stage,
            tasks=[],
            worker_task_count=0,
            evidence=[],
            usage=UsageDelta().model_dump(mode="json"),
            revision_round=0,
            should_revise=False,
            revision_blocked_reason=None,
            citations=[],
        )

    @staticmethod
    def _failure_status(failure: Exception) -> tuple[WorkflowStatus, str]:
        if isinstance(failure, RunCancelledError):
            return WorkflowStatus.CANCELLED, "CANCELLED"
        if isinstance(failure, RunTimedOutError | TimeoutError):
            return WorkflowStatus.TIMED_OUT, "TIMED_OUT"
        if isinstance(failure, RunBudgetExceededError):
            return WorkflowStatus.BUDGET_EXCEEDED, "BUDGET_EXCEEDED"
        if isinstance(failure, WorkflowExecutionError):
            return WorkflowStatus.FAILED, failure.error_code
        return WorkflowStatus.FAILED, "WORKFLOW_FAILED"

    @classmethod
    def _log_failure(cls, run_id: str, failure: Exception) -> None:
        safe_run_id = cls._safe_log_scalar(run_id)
        _, error_code = cls._failure_status(failure)
        try:
            chain = cls._exception_chain(failure)
            provider_status = cls._exception_attribute(chain, "status_code")
            request_id = cls._exception_attribute(chain, "request_id")
            provider_code = cls._exception_attribute(chain, "code")
            if provider_code is None:
                for item in chain:
                    try:
                        body = getattr(item, "body", None)
                    except Exception:
                        continue
                    if type(body) is dict:
                        provider_code = body.get("code") or body.get("type")
                        if provider_code is not None:
                            break
            operation = cls._exception_attribute(chain, "operation_key")
            attempt = cls._exception_attribute(chain, "attempt")
            model_failure_kind = cls._exception_attribute(chain, "failure_kind")
            model_error_class = cls._exception_attribute(chain, "error_class")
            retryable = cls._exception_attribute(chain, "retryable")
            schema_name = cls._exception_attribute(chain, "schema_name")
            parser_error_type = cls._exception_attribute(chain, "parser_error_type")
            finish_reason = cls._exception_attribute(chain, "finish_reason")
            content_state = cls._exception_attribute(chain, "content_state")
            tool_call_count = cls._exception_attribute(chain, "tool_call_count")
            validation_issue_codes = cls._exception_attribute(
                chain, "validation_issue_codes"
            )
            from .agent_diagnostics import safe_requirement_code, safe_validation_stage

            validation_stage = safe_validation_stage(
                cls._exception_attribute(chain, "validation_stage")
            )
            domain_error_code = safe_requirement_code(
                cls._exception_attribute(chain, "domain_error_code")
            )
            cause_chain = ">".join(cls._safe_log_scalar(type(item).__name__) for item in chain)
            logger.error(
                "workflow execution failed run_id=%s error_code=%s operation=%s "
                "attempt=%s model_failure_kind=%s model_error_class=%s retryable=%s "
                "failure_type=%s cause_chain=%s provider_status=%s provider_code=%s "
                "request_id=%s schema=%s parser_error_type=%s finish_reason=%s "
                "content_state=%s tool_call_count=%s validation_issues=%s location=%s "
                "validation_stage=%s domain_error_code=%s",
                safe_run_id,
                cls._safe_log_scalar(error_code),
                cls._safe_log_scalar(operation),
                cls._safe_log_scalar(attempt),
                cls._safe_log_scalar(model_failure_kind),
                cls._safe_log_scalar(model_error_class),
                cls._safe_log_scalar(str(retryable).lower() if retryable is not None else None),
                cls._safe_log_scalar(type(failure).__name__),
                cause_chain,
                cls._safe_log_scalar(provider_status),
                cls._safe_log_scalar(provider_code),
                cls._safe_log_scalar(request_id),
                cls._safe_log_scalar(schema_name),
                cls._safe_log_scalar(parser_error_type),
                cls._safe_log_scalar(finish_reason),
                cls._safe_log_scalar(content_state),
                cls._safe_log_scalar(tool_call_count),
                cls._safe_log_scalar(validation_issue_codes),
                cls._safe_log_scalar(cls._failure_location(failure)),
                cls._safe_log_scalar(validation_stage),
                cls._safe_log_scalar(domain_error_code),
            )
        except Exception:
            # Diagnostic extraction must never prevent durable failure finalization.
            logger.error(
                "workflow execution failed run_id=%s error_code=%s metadata_status=FAILED",
                safe_run_id,
                cls._safe_log_scalar(error_code),
            )

    @classmethod
    def _log_finalize_unavailable(cls, run_id: str, phase: str, failure: httpx.HTTPError) -> None:
        logger.warning(
            "workflow finalization unavailable run_id=%s phase=%s failure_type=%s",
            cls._safe_log_scalar(run_id),
            cls._safe_log_scalar(phase),
            cls._safe_log_scalar(type(failure).__name__),
        )

    @staticmethod
    def _exception_chain(failure: Exception) -> list[BaseException]:
        chain: list[BaseException] = []
        seen: set[int] = set()
        current: BaseException | None = failure
        while current is not None and id(current) not in seen and len(chain) < 8:
            seen.add(id(current))
            chain.append(current)
            current = current.__cause__
            if current is None and not chain[-1].__suppress_context__:
                current = chain[-1].__context__
        return chain

    @staticmethod
    def _exception_attribute(chain: list[BaseException], attribute: str) -> Any | None:
        for failure in chain:
            try:
                value = getattr(failure, attribute, None)
            except Exception:
                continue
            if value is not None:
                return value
        return None

    @staticmethod
    def _safe_log_scalar(value: Any | None) -> str:
        if value is None:
            return "none"
        if type(value) is int:
            text = str(value)
        elif type(value) is str:
            text = value
        else:
            return "redacted"
        if not text or len(text) > 96:
            return "redacted"
        if not text.isascii() or not all(
            character.isalnum() or character in "._:->" for character in text
        ):
            return "redacted"
        return text

    @staticmethod
    def _failure_location(failure: Exception) -> str:
        frames = traceback.extract_tb(failure.__traceback__)
        if not frames:
            return "unknown"
        frame = frames[-1]
        return f"{Path(frame.filename).name}:{frame.lineno}:{frame.name}"

    @staticmethod
    def _wire_usage(usage: UsageDelta, started: float) -> dict[str, Any]:
        return {
            "inputTokens": usage.input_tokens,
            "outputTokens": usage.output_tokens,
            "totalTokens": usage.total_tokens,
            "modelCalls": usage.model_calls,
            "toolCalls": usage.tool_calls,
            "estimatedCost": usage.cost_cny,
            "currency": "CNY",
            "durationMs": max(0, int((time.monotonic() - started) * 1000)),
        }

    async def _result_wire_usage(self,run,usage,started):
        if not isinstance(run.budget,AgentRunBudget):
            return self._wire_usage(usage,started)
        from .agent_budget import SqlAgentLedger
        ledger = (
            self._agent_ledger_factory(self._repository)
            if self._agent_ledger_factory
            else SqlAgentLedger(self._repository)
        )
        value=await ledger.summary(run.run_id,run.claim_token)
        value["totalTokens"] = (
            None
            if value["inputTokens"] is None or value["outputTokens"] is None
            else value["inputTokens"] + value["outputTokens"]
        )
        value["durationMs"]=max(0,int((time.monotonic()-started)*1000))
        return value

    @staticmethod
    def _safe_failure_message(failure: Exception) -> str:
        if isinstance(failure, WorkflowExecutionError):
            return str(failure).replace("\n", " ")[:500]
        return "workflow execution failed; inspect sidecar logs using the run id"
