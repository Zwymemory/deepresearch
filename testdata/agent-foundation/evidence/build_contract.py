"""Generate B's closed, local-reference knowledge contract (no network)."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ID = {"type": "string", "minLength": 1, "maxLength": 128, "pattern": r"^\S+$"}
TEXT = {"type": "string", "minLength": 1, "maxLength": 10000, "pattern": r"\S"}
TIME = {"type": "string", "format": "date-time"}
HASH = {"type": "string", "pattern": "^[a-f0-9]{64}$"}


def obj(properties, required=None):
    return {"type": "object", "properties": properties,
            "required": list(properties) if required is None else required,
            "additionalProperties": False}


def arr(items, minimum=0):
    return {"type": "array", "items": items, "minItems": minimum, "maxItems": 100,
            "uniqueItems": True}


def enum(*values):
    return {"enum": list(values)}


def ref(name):
    return {"$ref": "#/$defs/" + name}


def nullable(value):
    return {"oneOf": [value, {"type": "null"}]}


def known(value):
    return {"oneOf": [obj({"status": {"const": "known"}, "value": value}),
                      obj({"status": {"const": "unknown"}, "value": {"type": "null"}, "reason": TEXT})]}


def record(name, properties):
    return obj({"record_type": {"const": name}, "schema_version": {"const": "0.1.0"},
                "tenant_id": ID, "owner_id": ID, "project_id": ID, **properties})


def build():
    freshness = enum("fresh", "needs_recheck", "expired", "superseded")
    verdict = enum("supported", "refuted", "contested", "insufficient")
    applicability = obj({"subject": TEXT, "version": known(TEXT), "valid_at": known(TIME),
                         "conditions": arr(TEXT)})
    quote = obj({"start": {"type": "integer", "minimum": 0},
                 "end": {"type": "integer", "minimum": 1}, "text": TEXT, "sha256": HASH})
    locator = {"oneOf": [
        obj({"kind": {"const": "web_uri"}, "uri": {"type": "string", "format": "uri"}}),
        obj({"kind": {"const": "knowledge_chunk"}, "dataset_id": ID, "document_id": ID, "chunk_id": ID}),
        obj({"kind": {"const": "fixture_file"}, "path": TEXT}),
        obj({"kind": {"const": "test_result"}, "artifact_id": ID})]}
    source = obj({"source_id": ID, "kind": enum("web", "knowledge", "synthetic_fixture", "controlled_test"),
                  "title": TEXT, "locator": locator, "version": known(TEXT),
                  "published_at": known(TIME), "observed_at": TIME,
                  "source_group": known(ID), "derivation": enum("original", "mirror", "repost", "derived", "unknown"),
                  "parent_source_id": nullable(ID), "authority": enum("unassessed", "fixture_label", "reviewed")})
    evidence = record("Evidence", {
        "evidence_id": ID, "version": {"type": "integer", "minimum": 1}, "run_id": ID, "task_id": ID,
        "receipt_id": ID, "source": source,
        "snapshot": obj({"kind": enum("search_summary", "document_chunk", "full_text", "test_observation"),
                         "text": TEXT, "sha256": HASH, "encoding": {"const": "utf-8"},
                         "offset_unit": {"const": "unicode_codepoint"}}),
        "applicability": applicability, "retrieval_score": known({"type": "number", "minimum": 0}),
        "freshness": freshness, "availability": enum("available", "unavailable", "deleted"),
        "validity": enum("unassessed", "invalidated"), "invalidation_reason": nullable(TEXT)})
    relation = obj({"evidence_id": ID, "relation": enum("supports", "refutes", "insufficient"),
                    "quote": quote, "assessment_method": enum("fixture_label", "model_proposal", "human_review", "controlled_test"),
                    "assessment_ref": ID})
    claim = record("Claim", {"claim_id": ID, "run_id": ID, "text": TEXT,
                              "kind": enum("factual", "inference", "recommendation"),
                              "applicability": applicability, "evidence_links": arr(relation),
                              "decision_status": verdict, "freshness": freshness})
    decision = record("DecisionRecord", {
        "decision_id": ID, "claim_id": ID, "run_id": ID, "decision_status": verdict,
        "adopted_evidence_ids": arr(ID),
        "dismissed_evidence": arr(obj({"evidence_id": ID, "reason": TEXT})),
        "unresolved_evidence_ids": arr(ID), "rationale": TEXT, "gaps": arr(TEXT),
        "policy_version": {"const": "0.1.0"}, "recorded_at": TIME,
        "assessment_method": enum("fixture_label", "model_proposal", "human_review", "controlled_test")})
    challenge = record("Challenge", {
        "challenge_id": ID, "claim_id": ID, "run_id": ID, "task_id": ID,
        "kind": enum("missing_support", "incorrect_source", "version_conflict", "direct_conflict"),
        "status": enum("open", "responded", "resolved", "unresolved"), "evidence_ids": arr(ID),
        "requested_actions": arr(enum("search", "read_source", "recheck_version", "seek_counterevidence", "stop_with_gaps"), 1),
        "response_decision_id": nullable(ID), "remaining_gaps": arr(TEXT), "recorded_at": TIME})
    packet = record("ResearchPacket", {
        "packet_id": ID, "run_id": ID, "task_id": ID, "context_id": nullable(ID),
        "status": enum("complete", "partial"), "claim_ids": arr(ID), "evidence_ids": arr(ID),
        "decision_ids": arr(ID), "challenge_ids": arr(ID), "limitations": arr(TEXT), "gaps": arr(TEXT),
        "recorded_at": TIME})
    dependency = obj({"record_type": enum("Evidence", "Claim", "DecisionRecord", "MemoryItem"),
                      "record_id": ID, "version": {"type": "integer", "minimum": 1}, "snapshot_sha256": nullable(HASH)})
    progress = obj({"goal": TEXT, "completed_task_ids": arr(ID), "pending_task_ids": arr(ID),
                    "excluded_routes": arr(TEXT), "unresolved_claim_ids": arr(ID), "gaps": arr(TEXT)})
    result = obj({"summary": TEXT, "claim_ids": arr(ID, 1), "decision_ids": arr(ID, 1),
                  "evidence_ids": arr(ID, 1), "limitations": arr(TEXT)})
    memory = record("MemoryItem", {
        "memory_id": ID, "memory_type": enum("research_progress", "reusable_result"),
        "version": {"type": "integer", "minimum": 1}, "previous_version": nullable({"type": "integer", "minimum": 1}),
        "lifecycle": enum("active", "deleted"), "freshness": freshness,
        "applicability": applicability, "progress": nullable(progress), "result": nullable(result),
        "origin": obj({"run_ids": arr(ID, 1), "session_ids": arr(ID, 1), "packet_ids": arr(ID)}),
        "dependencies": arr(dependency), "created_at": TIME, "updated_at": TIME,
        "review": obj({"reviewed_at": known(TIME), "due_at": known(TIME),
                       "policy": enum("on_dependency_change", "time_bound", "manual_recheck")}),
        "idempotency_key": ID,
        "tombstone": nullable(obj({"deleted_at": TIME, "reason": TEXT, "version": {"type": "integer", "minimum": 1}}))})
    memory["allOf"] = [
        {"if": {"properties": {"lifecycle": {"const": "deleted"}}},
         "then": {"properties": {"progress": {"type": "null"}, "result": {"type": "null"},
                                  "tombstone": {"type": "object"}}},
         "else": {"properties": {"tombstone": {"type": "null"}},
                  "allOf": [{"if": {"properties": {"memory_type": {"const": "research_progress"}}},
                             "then": {"properties": {"progress": {"type": "object"}, "result": {"type": "null"}}},
                             "else": {"properties": {"progress": {"type": "null"}, "result": {"type": "object"}}}}]}}]
    definitions = dict(Evidence=evidence, Claim=claim, DecisionRecord=decision, Challenge=challenge,
                       ResearchPacket=packet, MemoryItem=memory)
    record_names = tuple(definitions)
    definitions.update(Measurement=known({"type": "number", "minimum": 0}), KnownTime=known(TIME))
    return {"$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": "https://deepresearch.local/contracts/agent/v0/knowledge.schema.json",
            "title": "Proposed Agent knowledge records 0.1.0",
            "$comment": "Round0 executable structure/integrity contract; no semantic adjudication or deployed memory API.",
            "oneOf": [ref(name) for name in record_names], "$defs": definitions}


if __name__ == "__main__":
    path = ROOT / "contracts/agent/v0/knowledge.schema.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n")
