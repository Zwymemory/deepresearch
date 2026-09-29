"""Join A runtime records and B fixture records; no live memory or Agent execution."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

from validate_runtime import ContractError, ID_FIELDS, ROOT, load_json, memory_preview, validate_bundle


def validate_bridge(peer_root: Path, peer):
    peer_schema = load_json(peer_root / "contracts/agent/v0/knowledge.schema.json")
    source = peer_root / "testdata/agent-foundation/memory/reuse-current-version.json"
    knowledge = load_json(source)
    runtime = load_json(ROOT / "testdata/agent-foundation/runtime/runtime-foundation.json")
    memory = next(row for row in knowledge["records"] if row["record_type"] == "MemoryItem")
    context = next(row for row in runtime["records"] if row["record_type"] == "AgentContext")
    selected = next(entry for entry in context["entries"] if entry["kind"] == "memory")
    preview, clipped = memory_preview(memory)
    selected.update(source_memory_id=memory["memory_id"], source_memory_version=memory["version"],
                    purpose="result_reuse", content=preview, truncated=clipped)
    task = next(row for row in runtime["records"] if row["record_type"] == "Task")
    task.update(status="done", failure=None)
    runtime["records"][-1].update(event_type="TASK_DONE", after_state="done")
    own_keys = {(row["record_type"], row[ID_FIELDS[row["record_type"]]])
                for row in [*runtime["records"], *runtime["external_refs"]]}
    extras = [row for row in knowledge["external_refs"]
              if (row["record_type"], row[ID_FIELDS[row["record_type"]]]) not in own_keys]
    runtime["external_refs"].extend(extras)
    project = next(row for row in runtime["records"] if row["record_type"] == "ResearchProject")
    # B consumes reference records, with matching full A records where available.
    trusted_for_b = [project, task, context, *runtime["external_refs"]]
    packet = next(row for row in knowledge["records"] if row["record_type"] == "ResearchPacket")
    packet["context_id"] = context["context_id"]
    b_result = peer.validate_bundle(knowledge, authorized_scope=knowledge["authorized_scope"], external_registry=trusted_for_b)
    a_result = validate_bundle(runtime, authorized_scope=runtime["authorized_scope"],
            knowledge_records=knowledge["records"], knowledge_schema=peer_schema)
    negatives = []

    def rejected(runtime_case, knowledge_rows, expected):
        try:
            validate_bundle(runtime_case, authorized_scope=runtime_case["authorized_scope"],
                            knowledge_records=knowledge_rows, knowledge_schema=peer_schema)
        except ContractError as error:
            assert error.code == expected, (error.code, expected)
            negatives.append(expected)
        else:
            raise AssertionError("bridge counterexample was accepted")

    mismatch = copy.deepcopy(runtime)
    mismatch_context = next(row for row in mismatch["records"] if row["record_type"] == "AgentContext")
    mismatch_context["entries"][2]["source_memory_version"] += 1
    rejected(mismatch, knowledge["records"], "MEMORY_VERSION_MISMATCH")
    mismatched_text = copy.deepcopy(runtime)
    next(row for row in mismatched_text["records"] if row["record_type"] == "AgentContext")["entries"][2]["content"] = "Invented text attached to a real memory ID."
    rejected(mismatched_text, knowledge["records"], "MEMORY_CONTENT_BINDING_INVALID")
    wrong_owner = copy.deepcopy(knowledge["records"])
    next(row for row in wrong_owner if row["record_type"] == "MemoryItem")["owner_id"] = "other-owner"
    rejected(runtime, wrong_owner, "REFERENCE_SCOPE_MISMATCH")
    stale = copy.deepcopy(knowledge["records"])
    next(row for row in stale if row["record_type"] == "MemoryItem")["freshness"] = "needs_recheck"
    rejected(runtime, stale, "MEMORY_RECHECK_REQUIRED")
    lead = copy.deepcopy(runtime)
    lead_entry = next(row for row in lead["records"] if row["record_type"] == "AgentContext")["entries"][2]
    stale_memory = next(row for row in stale if row["record_type"] == "MemoryItem")
    preview, clipped = memory_preview(stale_memory)
    lead_entry.update(purpose="research_lead", content=preview, truncated=clipped)
    validate_bundle(lead, authorized_scope=lead["authorized_scope"], knowledge_records=stale, knowledge_schema=peer_schema)
    deleted_source = peer_root / "testdata/agent-foundation/memory/deletion-tombstone.json"
    deleted = load_json(deleted_source)
    rejected(runtime, deleted["records"], "MEMORY_NOT_REUSABLE")
    return {"status": "cross_contract_fixture_refs_passed", "runtime": a_result, "knowledge": b_result,
            "negative_rejections": negatives, "stale_lead_allowed_as_untrusted_context": True,
            "peer_fixture_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "peer_deleted_fixture_sha256": hashlib.sha256(deleted_source.read_bytes()).hexdigest(),
            "model_use_verified": False, "live_memory_access": False, "runtime_execution": False}
