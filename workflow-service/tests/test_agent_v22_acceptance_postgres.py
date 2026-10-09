"""Actual acceptance harness functions on AgentAcceptanceV22PostgresIT's disposable PG."""

from __future__ import annotations

import copy
import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlparse

import psycopg
import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_completion import ensure_criteria
from deepresearch_workflow.agent_protocol import (
    AgentRunBudget,
    AgentTask,
    ModelRequest,
    ModelResult,
)
from deepresearch_workflow.agent_question_segments import (
    PLANNER_VERSION,
    planner_binding,
    question_segments,
    segment_drafts,
)
from deepresearch_workflow.agent_requirements import bind_requirements, freeze_requirements

from .test_agent_postgres import seed

SCRIPTS = Path(__file__).resolve().parents[2] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import agent_acceptance_v22 as native  # noqa: E402 - committed harness path

spec = importlib.util.spec_from_file_location("accept_v22_sql", SCRIPTS / "accept-agent-round1.py")
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)
fixtures_spec = importlib.util.spec_from_file_location(
    "v22_sql_fixtures", SCRIPTS / "tests/test_agent_v22_readiness.py"
)
fixtures = importlib.util.module_from_spec(fixtures_spec)
fixtures_spec.loader.exec_module(fixtures)
URL = os.getenv("TEST_AGENT_DATABASE_URL", "")
pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not URL or os.getenv("AGENT_TEST_ISOLATED") != "1",
        reason="disposable migrated SQL required",
    ),
]


def capture(run):
    location = urlparse(URL)
    return live.capture_database(
        {"database_port": location.port}, {"POSTGRES_PASSWORD": location.password}, run
    )


async def native_seed(*, manifest=True, segments=False):
    budget = AgentRunBudget(runtime="agent")
    repository, ledger, run, claim = await seed(budget)
    question = "Verify encryption and retention."
    drafts = [
        {
            "text": text,
            "kind": "factual",
            "question_spans": [{"start": 0, "end": len(question)}],
            "applicability": {
                "subject": text,
                "version": {"status": "known", "value": "2.0"},
                "valid_at": {"status": "unknown", "value": None, "reason": "Date unknown"},
                "conditions": [],
            },
        }
        for text in ("Verify encryption", "Verify retention")
    ]
    with psycopg.connect(URL) as conn:
        conn.execute("UPDATE agent_workflow_run SET question=%s WHERE run_id=%s", (question, run))
    tasks = [
        AgentTask(
            task_id="task-native",
            objective="Verify both original obligations",
            acceptance_criteria=[d["text"] for d in drafts],
            plan_version=1,
        ).model_dump(mode="json")
    ]
    ensure_criteria(run, tasks)
    await ledger.save_tasks(run, claim, tasks)
    if manifest:
        wire_drafts = copy.deepcopy(drafts)
        mapping = question_segments(question) if segments else None
        if segments:
            for draft in wire_drafts:
                draft.pop("question_spans")
                draft["segment_ids"] = [s["segment_id"] for s in mapping["segments"]]

        class DeclaringModel:
            async def invoke(self, _):
                value = {"requirements": wire_drafts}
                if segments:
                    value["planner_contract"] = PLANNER_VERSION
                return ModelResult(value=value, input_tokens=7, output_tokens=9)

        async def guard():
            pass

        gateway = AgentBudgetGateway(
            run_id=run,
            claim_token=claim,
            budget=budget,
            ledger=ledger,
            model=DeclaringModel(),
            guard=guard,
        )
        await gateway.model_call(
            "model:declaration",
            "DECISION",
            ModelRequest(
                name="AgentDecision",
                payload={"original_question": question},
                schema={"type": "object"},
                instruction="Declare all original standards",
                request_binding=planner_binding(mapping) if segments else {},
            ),
            lambda value: freeze_requirements(
                run, question, segment_drafts(question, value["requirements"])
                if segments else value["requirements"]
            ),
            canonicalize=(lambda value: {
                "planner_contract": PLANNER_VERSION,
                "requirements": segment_drafts(question, value["requirements"])
            })
            if segments else None,
        )
        frozen = freeze_requirements(run, question, drafts)
        by_text = {c["text"]: c["criterion_id"] for c in tasks[0]["criteria"]}
        links = bind_requirements(
            frozen,
            tasks,
            [
                {"requirement_id": r["requirement_id"], "criterion_id": by_text[r["text"]]}
                for r in frozen["requirements"]
            ],
        )
        await ledger.save_requirements(run, claim, frozen, links, tasks, "model:declaration")
    await repository.close()
    return run, claim


def test_actual_migrated_schema_matches_committed_checksums_tables_and_role():
    with tempfile.TemporaryDirectory() as directory:
        policy = native.candidate_schema_policy(fixtures.built_fixture(directory))
    with psycopg.connect(URL) as conn:
        assert native.verify_database_schema(conn, policy)["migration_version"] == 26
        for sql in (
            "DELETE FROM flyway_schema_history WHERE version='22'",
            "DELETE FROM flyway_schema_history WHERE version='11'",
            "UPDATE flyway_schema_history SET checksum=0 WHERE version='22'",
            "ALTER TABLE agent_research_requirement_binding RENAME TO hidden_requirement_binding",
            "ALTER TABLE agent_research_requirements DISABLE TRIGGER agent_requirements_immutable",
            "ALTER TABLE agent_research_requirements DROP CONSTRAINT "
            "agent_research_requirements_pkey CASCADE",
            "ALTER ROLE deepresearch_workflow CREATEDB",
            "ALTER ROLE deepresearch_workflow NOLOGIN",
            "ALTER TABLE agent_research_requirements ALTER COLUMN declaration_attempt "
            "DROP NOT NULL",
            "GRANT UPDATE ON agent_research_requirements TO deepresearch_workflow",
        ):
            with conn.transaction(force_rollback=True):
                conn.execute(sql)
                with pytest.raises(ValueError):
                    native.verify_database_schema(conn, policy)
        assert native.verify_database_schema(conn, policy)["required_tables_and_role_verified"]


@pytest.mark.asyncio
async def test_actual_run_scoped_native_export_preserves_two_persisted_declarations():
    run, claim = await native_seed()
    other, other_claim = await native_seed()
    exported = capture(run)
    proof = native.native_requirements_validation(exported)
    assert proof["status"] == "verified_mapping", proof
    assert proof["mapping_complete"]
    assert not proof["eligible_for_complete_review"]  # No checked evidence yet.
    assert len(exported["requirement_bindings"]) == 2
    assert exported["requirements"][0]["declaration_key"] == "model:declaration"
    assert exported["requirements"][0]["declaration_attempt"] == 1
    assert (
        exported["requirements"][0]["manifest"]["question_sha256"]
        == native.hashlib.sha256(exported["run"][0]["question"].encode()).hexdigest()
    )
    wire = json.dumps(exported, default=str)
    assert other not in wire and other_claim not in wire and claim not in wire
    assert all(b["run_id"] == run for b in exported["requirement_bindings"])
    audit = native.finalize_audit(live.scrub({"database": live.audit_database(exported)}, []))
    assert native.validate_saved_audit(json.loads(json.dumps(audit, default=str)))[
        "mapping_complete"
    ]


async def test_actual_segment_receipt_persistence_and_safe_audit_reconstruction():
    run, _ = await native_seed(segments=True)
    exported = capture(run)
    proof = native.native_requirements_validation(exported)
    assert proof["status"] == "verified_mapping" and proof["mapping_complete"], proof
    assert not proof["eligible_for_complete_review"]
    declared = next(o for o in exported["operations"] if o["purpose"] == "DECISION")
    assert declared["safe_result"]["request_binding"]["planner_contract"] == PLANNER_VERSION
    wire = json.loads(declared["safe_result"]["request_binding"]["planner_declaration"])
    assert "segment_ids" in wire["requirements"][0]
    assert "question_spans" in declared["safe_result"]["value"]["requirements"][0]
    assert (exported["requirements"][0]["manifest"]["contract_version"]
            == "agent-original-requirements/1")
    altered = copy.deepcopy(exported)
    altered["operations"][0]["safe_result"]["request_binding"]["question_mapping_sha256"] = "a" * 64
    assert native.native_requirements_validation(altered)["status"] == "invalid"
    audit = native.finalize_audit(live.scrub({"database": live.audit_database(exported)}, []))
    assert native.validate_saved_audit(json.loads(json.dumps(audit, default=str)))[
        "mapping_complete"
    ]


@pytest.mark.asyncio
async def test_actual_missing_binding_and_changed_original_question_are_not_complete():
    run, _ = await native_seed()
    with psycopg.connect(URL) as conn:
        conn.execute(
            "DELETE FROM agent_research_requirement_binding WHERE run_id=%s AND "
            "requirement_id=(SELECT min(requirement_id) FROM "
            "agent_research_requirement_binding WHERE run_id=%s)",
            (run, run),
        )
    proof = native.native_requirements_validation(capture(run))
    assert proof["status"] == "incomplete_mapping" and len(proof["missing_requirement_ids"]) == 1
    assert not proof["eligible_for_complete_review"]
    with psycopg.connect(URL) as conn:
        conn.execute(
            "UPDATE agent_workflow_run SET question='A different original question' "
            "WHERE run_id=%s",
            (run,),
        )
    proof = native.native_requirements_validation(capture(run))
    assert proof["status"] == "invalid" and not proof["eligible_for_complete_review"]


@pytest.mark.asyncio
async def test_actual_preplanning_failure_without_manifest_is_readable_but_uncovered():
    run, _ = await native_seed(manifest=False)
    with psycopg.connect(URL) as conn:
        conn.execute(
            "UPDATE agent_workflow_run SET status='FAILED',stage='FAILED',"
            "error_code='AGENT_MODEL_INVALID' WHERE run_id=%s",
            (run,),
        )
    exported = capture(run)
    assert exported["run"][0]["error_code"] == "AGENT_MODEL_INVALID"
    proof = native.native_requirements_validation(exported)
    assert proof["status"] == "missing_manifest" and not proof["eligible_for_complete_review"]
    legacy = {"database": copy.deepcopy(exported)}
    for key in ("capture", "requirements", "requirement_bindings"):
        legacy["database"].pop(key)
    assert native.validate_saved_audit(legacy)["status"] == "legacy_audit"


@pytest.mark.asyncio
async def test_actual_capture_is_all_or_nothing_when_safe_row_or_byte_bounds_exceeded():
    run, _ = await native_seed()
    with psycopg.connect(URL) as conn:
        for index in range(65):
            conn.execute(
                "INSERT INTO agent_research_task(run_id,task_id,objective,status,"
                "acceptance_criteria,plan_version,task_json,claim_token) "
                "SELECT run_id,%s,'extra','pending',ARRAY['bound'],1,'{}',claim_token "
                "FROM agent_workflow_run WHERE run_id=%s",
                ("extra-" + str(index), run),
            )
    exported = capture(run)
    assert exported["capture"]["status"] == "incomplete"
    assert exported["capture"]["error_code"] == "CAPTURE_BOUND_EXCEEDED"
    assert "requirements" not in exported and exported["operations"] == []
    assert not native.native_requirements_validation(exported)["eligible_for_complete_review"]
    for size, count in ((1048600, 1), (1000000, 9)):
        other, claim = await native_seed()
        with psycopg.connect(URL) as conn:
            for index in range(count):
                key = "tool:large-" + str(index)
                conn.execute(
                    "INSERT INTO agent_research_operation(run_id,operation_key,attempt,kind,"
                    "purpose,request_hash,status,input_reserved,output_reserved,claim_token) "
                    "VALUES (%s,%s,1,'TOOL','TOOL',%s,'RESERVED',0,0,%s::uuid)",
                    (other, key, "f" * 64, claim),
                )
                conn.execute(
                    "UPDATE agent_research_operation SET status='SETTLED',safe_result='{}',"
                    "actual_usage=jsonb_build_object('synthetic_padding',repeat('x',%s)),"
                    "settled_at=now() WHERE run_id=%s AND operation_key=%s",
                    (size, other, key),
                )
        exported = capture(other)
        assert exported["capture"]["status"] == "incomplete"
        assert exported["capture"]["table"] == "operations"
        assert "requirements" not in exported and exported["operations"] == []
