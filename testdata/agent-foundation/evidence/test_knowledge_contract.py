"""Meaningful negative checks and explicit semantic limitations; stdlib only."""
import copy
import hashlib
import json
import unittest
from pathlib import Path

from validate_knowledge import (ContractError, ROOT, SCHEMA, check_schema, load_json,
                                source_metadata_hash,
                                validate_bundle, validate_manifest, validate_record,
                                validate_schema)

REJECTIONS = []


def fixture(name):
    return load_json(ROOT / ("testdata/agent-foundation/" + name + ".json"))


def row(bundle, kind, identity=None):
    return next(r for r in bundle["records"] if r["record_type"] == kind and
                (identity is None or identity in r.values()))


def bind_mutated_fixture_metadata(bundle, evidence):
    receipt = next(r for r in bundle["external_refs"] if r.get("receipt_id") == evidence["receipt_id"])
    receipt["source_bindings"] = [dict(source_id=evidence["source"]["source_id"], snapshot_sha256=evidence["snapshot"]["sha256"],
                                      source_metadata_sha256=source_metadata_hash(evidence["source"]))]


class KnowledgeContractTests(unittest.TestCase):
    def reject(self, bundle, code):
        with self.assertRaises(ContractError) as error:
            validate_bundle(bundle)
        self.assertTrue(error.exception.code.startswith(code), (code, error.exception.code))
        REJECTIONS.append({"test": self.id().split(".")[-1], "expected_code": code,
                           "observed_code": error.exception.code, "actually_rejected": True})

    def test_all_eleven_positive_fixtures(self):
        outputs = validate_manifest()
        self.assertEqual(11, len(outputs))
        self.assertTrue(all(not o["semantic_verification"] and not o["runtime_execution"] for o in outputs))

    def test_records_validate_against_actual_schema(self):
        schema = load_json(SCHEMA)
        check_schema(schema)
        kinds = set()
        for area in ("evidence", "memory"):
            manifest = load_json(ROOT / f"testdata/agent-foundation/{area}/manifest.json")
            for entry in manifest["cases"]:
                for record in load_json(ROOT / entry["path"])["records"]:
                    validate_record(record, schema)
                    kinds.add(record["record_type"])
        self.assertEqual({"Evidence", "Claim", "DecisionRecord", "Challenge", "ResearchPacket", "MemoryItem"}, kinds)

    def test_missing_evidence_reference(self):
        b = fixture("evidence/version-difference")
        row(b, "Claim")["evidence_links"][0]["evidence_id"] = "missing-evidence"
        self.reject(b, "REFERENCE_MISSING")

    def test_reusable_result_missing_source(self):
        b = fixture("memory/reuse-current-version")
        row(b, "MemoryItem")["result"]["evidence_ids"] = []
        self.reject(b, "STRUCTURE")

    def test_supported_cannot_mask_unresolved_conflict(self):
        b = fixture("evidence/unresolved-conflict")
        row(b, "Claim")["decision_status"] = "supported"
        row(b, "DecisionRecord")["decision_status"] = "supported"
        self.reject(b, "UNRESOLVED_CONFLICT")

    def test_unknown_publication_time_cannot_be_zero(self):
        b = fixture("evidence/version-difference")
        row(b, "Evidence")["source"]["published_at"]["value"] = 0
        self.reject(b, "STRUCTURE")

    def test_unknown_cost_cannot_be_zero(self):
        b = fixture("evidence/empty-retrieval")
        b["usage"]["cost"]["value"] = 0
        self.reject(b, "STRUCTURE")

    def test_measured_zero_is_distinct_and_valid(self):
        b = fixture("evidence/empty-retrieval")
        b["usage"]["cost"] = {"status": "known", "value": 0}
        self.assertFalse(validate_bundle(b)["semantic_verification"])

    def test_invalid_timestamp_or_missing_timezone(self):
        for value in ("2026-09-29", "2026-09-29T08:00:00", "2026-02-30T08:00:00Z"):
            with self.subTest(value=value):
                b = fixture("evidence/version-difference")
                row(b, "Evidence")["source"]["observed_at"] = value
                self.reject(b, "STRUCTURE")

    def test_snapshot_bytes_are_bound(self):
        b = fixture("evidence/version-difference")
        row(b, "Evidence")["snapshot"]["text"] += "changed"
        self.reject(b, "SNAPSHOT_HASH_MISMATCH")

    def test_snapshot_cannot_hide_a_different_fixture_file(self):
        b = fixture("evidence/version-difference")
        row(b, "Evidence")["source"]["locator"]["path"] = "testdata/agent-foundation/evidence/sources/version-v2.md"
        bind_mutated_fixture_metadata(b, row(b, "Evidence"))
        self.reject(b, "FIXTURE_SOURCE_MISMATCH")

    def test_fixture_path_cannot_escape(self):
        b = fixture("evidence/version-difference")
        row(b, "Evidence")["source"]["locator"]["path"] = "../../outside.md"
        bind_mutated_fixture_metadata(b, row(b, "Evidence"))
        self.reject(b, "FIXTURE_PATH_ESCAPE")

    def test_synthetic_source_cannot_be_labelled_real_web(self):
        b = fixture("evidence/version-difference")
        row(b, "Evidence")["source"]["kind"] = "web"
        bind_mutated_fixture_metadata(b, row(b, "Evidence"))
        self.reject(b, "SOURCE_KIND_MISMATCH")

    def test_exact_quote_range_and_hash(self):
        b = fixture("evidence/version-difference")
        quote = row(b, "Claim")["evidence_links"][0]["quote"]
        quote["start"] += 1
        self.reject(b, "QUOTE_RANGE_MISMATCH")
        b = fixture("evidence/version-difference")
        row(b, "Claim")["evidence_links"][0]["quote"]["sha256"] = "0" * 64
        self.reject(b, "QUOTE_HASH_MISMATCH")

    def test_unicode_offsets_are_codepoints_not_bytes(self):
        b = fixture("evidence/version-difference")
        ev = row(b, "Evidence")
        text = ev["snapshot"]["text"]
        quote = row(b, "Claim")["evidence_links"][0]["quote"]
        # The schema describes Unicode codepoints; the source and range remain original.
        self.assertEqual(text[quote["start"]:quote["end"]], quote["text"])
        self.assertEqual(hashlib.sha256(quote["text"].encode()).hexdigest(), quote["sha256"])
        prefix = text[:quote["start"]]
        self.assertGreater(len(prefix.encode()), len(prefix))
        self.assertGreater(len(prefix.encode("utf-16-le")) // 2, len(prefix))
        for offset in (len(prefix.encode()), len(prefix.encode("utf-16-le")) // 2):
            changed = copy.deepcopy(b)
            q = row(changed, "Claim")["evidence_links"][0]["quote"]
            q.update(start=offset, end=offset + len(q["text"]))
            self.reject(changed, "QUOTE_RANGE_MISMATCH")

    def test_source_metadata_cannot_be_replaced_inside_valid_receipt(self):
        b = fixture("evidence/version-difference")
        row(b, "Evidence")["source"]["title"] = "A different synthetic source"
        self.reject(b, "RECEIPT_SOURCE_MISMATCH")

    def test_receipt_must_be_completed_authorized_and_same_run(self):
        for key, value in (("authorized", False), ("status", "running"), ("run_id", "different-run")):
            with self.subTest(key=key):
                b = fixture("evidence/version-difference")
                next(r for r in b["external_refs"] if r["record_type"] == "Receipt")[key] = value
                self.reject(b, "RECEIPT_BINDING_INVALID")

    def test_cross_owner_record_and_cross_project_reference(self):
        b = fixture("evidence/version-difference")
        row(b, "Claim")["owner_id"] = "other-owner"
        self.reject(b, "AUTHORIZED_SCOPE_MISMATCH")
        b = fixture("evidence/version-difference")
        next(r for r in b["external_refs"] if r["record_type"] == "Receipt")["project_id"] = "other-project"
        self.reject(b, "REFERENCE_SCOPE_MISMATCH")

    def test_version_specific_source_not_universal(self):
        b = fixture("evidence/version-difference")
        row(b, "Claim")["applicability"]["version"] = {"status": "known", "value": "3.0"}
        self.reject(b, "EVIDENCE_VERSION_SCOPE_MISMATCH")

    def test_missing_runtime_registry_is_incomplete_not_pass(self):
        b = fixture("evidence/empty-retrieval")
        with self.assertRaisesRegex(ContractError, "REFERENCE_MISSING"):
            validate_bundle(b, external_registry=[])

    def test_closed_schema_rejects_unexpected_authority_fields(self):
        b = fixture("evidence/version-difference")
        row(b, "Evidence")["is_true"] = True
        self.reject(b, "STRUCTURE")

    def test_decision_cannot_adopt_a_refute_as_support(self):
        b = fixture("evidence/wrong-material")
        row(b, "Claim")["decision_status"] = "supported"
        row(b, "DecisionRecord")["decision_status"] = "supported"
        self.reject(b, "DECISION_RELATION_MISMATCH")

    def test_packet_reference_must_exist(self):
        b = fixture("evidence/version-difference")
        row(b, "ResearchPacket")["claim_ids"].append("missing-claim")
        self.reject(b, "REFERENCE_MISSING")

    def test_deleted_memory_blocks_old_checkpoint_candidate(self):
        b = fixture("memory/deletion-tombstone")
        self.assertEqual(1, b["checkpoint_candidate"]["version"])
        b["memory_requests"] = [{"memory_id":"fixture-memory", "version":1,"operation":"resume",
                                 "authorized_scope":b["authorized_scope"],"session_id":"fixture-new-session",
                                 "target_version":{"status":"known","value":"1.0"},"as_of":"2026-09-29T08:00:00Z"}]
        self.reject(b, "MEMORY_DELETED")

    def test_cross_user_tenant_project_access_denied_before_retrieval(self):
        for key in ("tenant_id", "owner_id", "project_id"):
            with self.subTest(key=key):
                b = fixture("memory/reuse-current-version")
                b["memory_requests"][0]["authorized_scope"][key] = "different-scope"
                self.reject(b, "MEMORY_ACCESS_DENIED")

    def test_new_session_continuation_requires_distinct_session(self):
        b = fixture("memory/session-continuation")
        b["memory_requests"][0]["session_id"] = "fixture-old-session"
        self.reject(b, "NEW_SESSION_CONTINUATION_REQUIRED")

    def test_changed_version_can_be_a_lead_but_not_reused_as_fact(self):
        b = fixture("memory/reuse-version-change")
        self.assertEqual(1, len(validate_bundle(b)["memory_requests"]))
        b["memory_requests"][0]["operation"] = "reuse_as_evidence"
        self.reject(b, "MEMORY_RECHECK_REQUIRED")

    def test_review_deadline_blocks_old_result(self):
        b = fixture("memory/reuse-current-version")
        b["memory_requests"][0]["as_of"] = "2026-10-01T08:00:00Z"
        self.reject(b, "MEMORY_RECHECK_REQUIRED")

    def test_invalidated_source_cannot_leave_memory_fresh(self):
        b = fixture("memory/evidence-invalidated")
        row(b, "MemoryItem")["freshness"] = "fresh"
        self.reject(b, "MEMORY_DEPENDENCY_CHANGED")

    def test_stale_claim_cannot_leave_result_memory_fresh(self):
        b = fixture("memory/reuse-current-version")
        row(b, "Claim", "claim-v2")["freshness"] = "needs_recheck"
        self.reject(b, "MEMORY_DEPENDENCY_CHANGED")

    def test_deleted_or_stale_memory_dependency_blocks_fresh_result(self):
        for state in ("deleted", "needs_recheck", "expired", "superseded"):
            with self.subTest(state=state):
                b = fixture("memory/reuse-current-version")
                current = row(b, "MemoryItem")
                parent = copy.deepcopy(current)
                parent["memory_id"] = "fixture-parent-memory"
                if state == "deleted":
                    parent = row(fixture("memory/deletion-tombstone"), "MemoryItem")
                    parent["memory_id"] = "fixture-parent-memory"
                else:
                    parent["freshness"] = state
                b["records"].append(parent)
                current["dependencies"].append({"record_type": "MemoryItem", "record_id": parent["memory_id"],
                                                "version": parent["version"], "snapshot_sha256": None})
                self.reject(b, "MEMORY_DEPENDENCY_CHANGED")

    def test_memory_revision_conflict(self):
        b = fixture("memory/reuse-current-version")
        b["memory_requests"][0]["version"] = 2
        self.reject(b, "MEMORY_VERSION_CONFLICT")

    def test_memory_dependency_and_decision_closure(self):
        b = fixture("memory/reuse-current-version")
        row(b, "MemoryItem")["dependencies"] = []
        self.reject(b, "MEMORY_DEPENDENCY_MISSING")
        b = fixture("memory/reuse-current-version")
        row(b, "MemoryItem")["result"]["decision_ids"] = ["decision-claim-v1"]
        row(b, "MemoryItem")["dependencies"].append({"record_type":"DecisionRecord", "record_id":"decision-claim-v1", "version":1,"snapshot_sha256":None})
        self.reject(b, "MEMORY_DECISION_CLAIM_MISMATCH")

    def test_memory_dependency_cycle_is_rejected(self):
        b = fixture("memory/reuse-current-version")
        row(b, "MemoryItem")["dependencies"].append({"record_type":"MemoryItem", "record_id":"fixture-memory", "version":1,"snapshot_sha256":None})
        self.reject(b, "MEMORY_DEPENDENCY_CYCLE")

    def test_idempotency_accepts_identical_and_rejects_changed_payload(self):
        b = fixture("memory/idempotent-write")
        validate_bundle(b)
        b["memory_writes"][1]["payload"]["content"] = "changed synthetic candidate"
        self.reject(b, "MEMORY_IDEMPOTENCY_CONFLICT")

    def test_write_revision_conflict(self):
        b = fixture("memory/idempotent-write")
        b["memory_writes"][0]["expected_version"] = 2
        self.reject(b, "MEMORY_WRITE_VERSION_CONFLICT")

    def test_unknown_schema_keywords_and_remote_refs_fail_closed(self):
        schema = load_json(SCHEMA)
        schema["custom_truth_validator"] = True
        with self.assertRaisesRegex(ContractError, "UNSUPPORTED_SCHEMA_KEYWORD"):
            check_schema(schema)
        with self.assertRaisesRegex(ContractError, "REMOTE_SCHEMA_REFERENCE_DENIED"):
            validate_schema({}, {"$ref":"https://invalid.example/schema.json"})

    def test_duplicate_keys_and_nonfinite_json_are_rejected(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            for value, code in [('{"same":1,"same":2}',"DUPLICATE_JSON_KEY"), ('{"value":NaN}',"NONFINITE_JSON_NUMBER")]:
                path.write_text(value)
                with self.assertRaisesRegex(ContractError, code):
                    load_json(path)

    def test_structure_and_identity_are_not_semantic_truth(self):
        b = fixture("evidence/version-difference")
        row(b, "Claim")["text"] = "This deliberately false sentence is NOT established by these quotes"
        # The offline checker does not parse prose or infer support. Preserve this limitation.
        outcome = validate_bundle(b)
        self.assertFalse(outcome["semantic_verification"])
        self.assertFalse(outcome["runtime_execution"])


if __name__ == "__main__":
    unittest.main()
