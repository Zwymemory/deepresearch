"""Actual preparation/export validation functions; no services/provider requests."""

import copy
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import agent_acceptance_v22 as native  # noqa: E402 - direct script loading
from agent_live_common import file_sha, write_private  # noqa: E402 - direct script loading

spec = importlib.util.spec_from_file_location(
    "prepare_v22", SCRIPTS / "prepare-agent-live-runtime.py"
)
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


def built_fixture(
    directory, identity_path="BOOT-INF/classes/META-INF/build-info.properties"
):
    root = Path(directory)
    source = root / "source"
    blobs = {}
    for path in sorted((SCRIPTS.parent / native.MIGRATIONS).glob("*.sql")):
        name = native.MIGRATIONS + path.name
        destination = source / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        data = path.read_bytes()
        blobs[name] = native.hashlib.sha1(
            b"blob " + str(len(data)).encode() + b"\0" + data
        ).hexdigest()
    manifest = root / "source-manifest.json"

    def git(*args):
        return (
            subprocess.check_output(
                ["git", "-C", str(source), *args], stderr=subprocess.DEVNULL
            )
            .decode()
            .strip()
        )

    git("init", "-q")
    git("add", ".")
    git(
        "-c",
        "user.name=Offline Fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "commit",
        "-qm",
        "Pinned migration fixture",
    )
    sha = git("rev-parse", "HEAD")
    write_private(manifest, {"code_sha": sha, "blobs": blobs})
    jar = root / "candidate.jar"
    with zipfile.ZipFile(jar, "w") as out:
        out.writestr(
            identity_path,
            "build.revision="
            + sha
            + "\nbuild.source-manifest-sha256="
            + file_sha(manifest)
            + "\nbuild.isolation-id="
            + native.ISOLATION_ID
            + "\n",
        )
        for name in blobs:
            out.write(source / name, "BOOT-INF/classes/db/migration/" + Path(name).name)
    return {
        "build_sha": sha,
        "git_repository": str(source),
        "source_archive": str(source),
        "source_manifest_path": str(manifest),
        "source_manifest_sha256": file_sha(manifest),
        "jar_path": str(jar),
        "jar_sha256": file_sha(jar),
    }


def native_fixture(task_id="task-main"):
    criterion_id, normalize, freeze, _, _ = native.workflow_helpers()
    question, run = "Verify encryption and retention.", "wf-export-unit"
    drafts = [
        {
            "text": text,
            "kind": "factual",
            "question_spans": [{"start": 0, "end": len(question)}],
            "applicability": {
                "subject": text,
                "version": {"status": "known", "value": "2.0"},
                "valid_at": {
                    "status": "unknown",
                    "value": None,
                    "reason": "Date unknown",
                },
                "conditions": [],
            },
        }
        for text in ("Verify encryption", "Verify retention")
    ]
    manifest = freeze(run, question, drafts)
    database = {k: [] for k in native.CAPTURE_LIMITS}
    database["run"] = [{"run_id": run, "question": question, "status": "SUCCEEDED"}]
    database["requirements"] = [
        {
            "run_id": run,
            "manifest": manifest,
            "declaration_key": "model:declaration",
            "declaration_attempt": 1,
        }
    ]
    database["operations"] = [
        {
            "operation_key": "model:declaration",
            "attempt": 1,
            "kind": "MODEL",
            "purpose": "DECISION",
            "status": "SETTLED",
            "safe_result": {"value": {"requirements": drafts}},
        }
    ]
    database["tasks"] = [
        {
            "task_id": task_id,
            "acceptance_criteria": [d["text"] for d in drafts],
            "dependencies": [],
            "status": "done",
        }
    ]
    evidence = {
        "record_type": "Evidence",
        "run_id": run,
        "evidence_id": "ev-original",
        "snapshot": {
            "text": "Synthetic original",
            "sha256": native.hashlib.sha256(b"Synthetic original").hexdigest(),
        },
    }
    payloads = [evidence]
    for index, draft in enumerate(drafts):
        expected = normalize(
            {
                "text": "Resolved " + draft["text"],
                "kind": draft["kind"],
                "applicability": draft["applicability"],
            }
        )
        identity = criterion_id(run, task_id, index, draft["text"])
        requirement = next(
            r for r in manifest["requirements"] if r["text"] == draft["text"]
        )
        investigation, call, check = (
            "investigation-" + str(index),
            "tool-check-" + str(index),
            "check-" + str(index),
        )
        database["requirement_bindings"].append(
            {
                "run_id": run,
                "requirement_id": requirement["requirement_id"],
                "task_id": task_id,
                "criterion_id": identity,
            }
        )
        database["criteria"].append(
            {
                "task_id": task_id,
                "criterion_id": identity,
                "criterion_index": index,
                "criterion_text": draft["text"],
                "expected_claim": expected,
                "expected_hash": native.digest(expected),
                "investigation": investigation,
                "last_call_id": call,
                "dependency_snapshot": {},
            }
        )
        claim = {
            **expected,
            "record_type": "Claim",
            "claim_id": "claim-" + str(index),
            "run_id": run,
            "freshness": "fresh",
            "decision_status": "supported",
            "evidence_links": [{"evidence_id": "ev-original", "relation": "supports"}],
        }
        decision = {
            "record_type": "DecisionRecord",
            "decision_id": "decision-" + str(index),
            "run_id": run,
            "claim_id": claim["claim_id"],
            "decision_status": "supported",
            "gaps": [],
            "unresolved_evidence_ids": [],
            "adopted_evidence_ids": ["ev-original"],
        }
        request = {
            "check_id": check,
            "investigation_id": investigation,
            "claims": [claim],
        }
        response = {"claims": [{"claim_id": claim["claim_id"], "relations": []}]}
        response_sha = native.digest(response)
        database["operations"].append(
            {
                "operation_key": "model-check-" + str(index),
                "kind": "MODEL",
                "purpose": "CHECK",
                "status": "SETTLED",
                "safe_result": {
                    "value": response,
                    "request_binding": {
                        "check_id": check,
                        "request_sha256": native.digest(request),
                        "response_sha256": response_sha,
                    },
                },
            }
        )
        database["checks"].append(
            {
                "check_id": check,
                "call_id": call,
                "task_id": task_id,
                "investigation": investigation,
                "status": "COMPLETED",
                "request": request,
                "request_sha256": native.digest(request),
                "response_sha256": response_sha,
                "result": {"records": [claim, decision]},
            }
        )
        database["investigation_progress"].append(
            {"investigation": investigation, "current_call_id": call}
        )
        database["operations"].append(
            {
                "operation_key": call,
                "attempt": 1,
                "kind": "TOOL",
                "purpose": "TOOL",
                "status": "SETTLED",
            }
        )
        payloads.extend([claim, decision])
    database["records"] = [
        {
            "record_type": p["record_type"],
            "payload": p,
            "payload_sha256": native.digest(p),
        }
        for p in payloads
    ]
    return recount(database)


def recount(database):
    database["capture"] = {
        "contract_version": native.CAPTURE_VERSION,
        "status": "complete",
        "run_id": database["run"][0]["run_id"],
        "row_limits": native.CAPTURE_LIMITS,
        "counts": {k: {"rows": len(database[k])} for k in native.CAPTURE_LIMITS},
    }
    return database


class SchemaTests(unittest.TestCase):
    def test_both_existing_jar_identity_layouts_work_but_missing_or_duplicate_fail(
        self,
    ):
        locations = (
            "BOOT-INF/classes/META-INF/build-info.properties",
            "META-INF/build-info.properties",
        )
        for location in locations:
            with tempfile.TemporaryDirectory() as directory:
                built = built_fixture(directory, location)
                self.assertEqual(
                    len(native.candidate_schema_policy(built)["migrations"]), 22
                )
                with zipfile.ZipFile(built["jar_path"], "a") as jar:
                    jar.writestr(
                        next(name for name in locations if name != location),
                        jar.read(location),
                    )
                built["jar_sha256"] = file_sha(built["jar_path"])
                with self.assertRaisesRegex(ValueError, "IDENTITY_RESOURCE_INVALID"):
                    native.candidate_schema_policy(built)
        with tempfile.TemporaryDirectory() as directory:
            built = built_fixture(directory)
            jar_path = Path(built["jar_path"])
            with zipfile.ZipFile(jar_path) as jar:
                retained = {
                    name: jar.read(name)
                    for name in jar.namelist()
                    if name not in locations
                }
            with zipfile.ZipFile(jar_path, "w") as jar:
                for name, data in retained.items():
                    jar.writestr(name, data)
            built["jar_sha256"] = file_sha(jar_path)
            with self.assertRaisesRegex(ValueError, "IDENTITY_RESOURCE_INVALID"):
                native.candidate_schema_policy(built)

    def test_verified_candidate_policy_and_tampered_local_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            built = built_fixture(directory)
            policy = native.candidate_schema_policy(built)
            self.assertEqual(
                [r["version"] for r in policy["migrations"]],
                [str(v) for v in range(1, 23)],
            )
            for key in ("build_sha", "source_manifest_sha256", "jar_sha256"):
                broken = {**built, key: "0" * len(built[key])}
                with self.subTest(key=key), self.assertRaises(ValueError):
                    native.candidate_schema_policy(broken)
            migration = (
                Path(built["source_archive"])
                / native.MIGRATIONS
                / policy["migrations"][-1]["script"]
            )
            migration.write_text(migration.read_text() + "\n-- uncommitted change")
            with self.assertRaisesRegex(ValueError, "CONTENT_MISMATCH"):
                native.candidate_schema_policy(built)

    def test_start_rejects_wrong_candidate_before_any_resource_or_http_operation(self):
        with (
            patch.object(prepare.subprocess, "run") as operation,
            patch.object(prepare, "http_json") as api,
        ):
            with self.assertRaisesRegex(ValueError, "CANDIDATE_IDENTITY_INVALID"):
                prepare.start(None, None, {}, None, None, None, "deepseek-flash")
            operation.assert_not_called()
            api.assert_not_called()

    def test_actual_schema_preflight_accepts_exact22_and_rejects_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            policy = native.candidate_schema_policy(built_fixture(directory))

        class Connection:
            defect = None

            def execute(self, sql, params=None):
                if "flyway_schema_history" in sql:
                    self.rows = [
                        (r["version"], "SQL", r["script"], r["checksum"], True)
                        for r in policy["migrations"]
                    ]
                    if self.defect == "21":
                        self.rows.pop()
                    if self.defect == "missing":
                        self.rows.pop(5)
                    if self.defect == "checksum":
                        self.rows[-1] = (*self.rows[-1][:3], 0, True)
                    if self.defect == "future":
                        self.rows.append(("23", "SQL", "V23__future.sql", 0, True))
                elif "pg_roles" in sql:
                    self.rows = [
                        (
                            False,
                            self.defect == "role",
                            False,
                            False,
                            self.defect != "nologin",
                        )
                    ]
                elif "has_schema_privilege" in sql:
                    self.rows = [(self.defect != "schema_usage",)]
                elif "information_schema" in sql:
                    self.rows = [
                        (name, kind, "YES" if self.defect == "nullable" else "NO")
                        for name, kind in native.TABLE_COLUMNS[params[0]].items()
                    ]
                    if self.defect == "table":
                        self.rows = []
                elif "pg_trigger" in sql:
                    self.rows = [
                        (t,)
                        for t in native.TABLE_TRIGGERS[
                            params[0].removeprefix("public.")
                        ]
                    ]
                    if self.defect == "trigger":
                        self.rows = []
                elif "pg_constraint" in sql:
                    self.rows = [
                        (kind, list(names), target, True)
                        for kind, names, target in native.TABLE_CONSTRAINTS[
                            params[0].removeprefix("public.")
                        ]
                    ]
                    if self.defect == "constraint":
                        self.rows.pop()
                else:
                    self.rows = [
                        (
                            params[1] in {"SELECT", "INSERT"}
                            or self.defect == "privilege",
                        )
                    ]
                return self

            def fetchall(self):
                return self.rows

            def fetchone(self):
                return self.rows[0] if self.rows else None

        conn = Connection()
        self.assertEqual(
            native.verify_database_schema(conn, policy)["migration_version"], 22
        )
        for defect in (
            "21",
            "missing",
            "checksum",
            "future",
            "role",
            "table",
            "trigger",
            "privilege",
            "constraint",
            "nullable",
            "nologin",
            "schema_usage",
        ):
            conn.defect = defect
            with self.subTest(defect=defect), self.assertRaises(ValueError):
                native.verify_database_schema(conn, policy)


def obligation_native_fixture(crossed=False):
    """Two different obligations share a scope; their reference identities stay distinct."""
    from deepresearch_workflow.agent_obligations import authoritative_context, obligation_drafts
    from deepresearch_workflow.agent_question_segments import question_segments, planner_binding
    from deepresearch_workflow.agent_requirements import canonical, freeze_requirements
    from deepresearch_workflow.agent_completion import normalized_claim

    db = native_fixture()
    db['records'][0]['payload']['source'] = {'locator': {'uri': 'https://docs.example.test/security'}}
    question = 'Verify encryption. Verify retention.'
    run = db['run'][0]['run_id']
    units = question_segments(question)['segments']
    scope = copy.deepcopy(db['criteria'][0]['expected_claim']['applicability'])
    scope['subject'] = 'Same product scope'
    wire = {'planner_contract': 'agent-planning-obligations/3', 'claims_contract': 'agent-obligation-claims/1',
            'action': 'finish', 'reason': 'Controlled two distinct research obligations', 'constraints': [],
            'obligations': [{'text': text, 'kind': 'factual', 'applicability': scope,
                             'segment_ids': [units[i]['segment_id']]}
                            for i, text in enumerate(('Verify encryption', 'Verify retention'))]}
    drafts = obligation_drafts(question, wire['obligations'], wire['constraints'])
    value = {'planner_contract': wire['planner_contract'], 'claims_contract': wire['claims_contract'],
             'requirements': drafts}
    receipt = {'value': value, 'request_binding': {**planner_binding(question_segments(question)),
               'planner_contract': wire['planner_contract'], 'claims_contract': wire['claims_contract'],
               'continuation_contract': 'agent-frozen-requirements/2', 'planning_phase': 'initial',
               'planner_declaration': canonical(wire), 'wire_response_sha256': native.digest(wire),
               'response_sha256': native.digest(value)}}
    manifest = freeze_requirements(run, question, drafts)
    db['run'][0]['question'] = question
    db['requirements'][0]['manifest'] = manifest
    db['operations'][0]['safe_result'] = receipt
    for i, c in enumerate(db['criteria']):
        req = next(r for r in manifest['requirements'] if r['text'] == drafts[i]['text'])
        db['requirement_bindings'][i]['requirement_id'] = req['requirement_id']
        c['expected_claim'] = normalized_claim({'text': 'Resolved ' + drafts[i]['text'], 'kind': 'factual', 'applicability': scope})
        c['expected_hash'] = native.digest(c['expected_claim'])
        claim = db['checks'][i]['result']['records'][0]
        claim.update(c['expected_claim'])
        for record in db['records']:
            if record['record_type'] == 'Claim' and record['payload']['claim_id'] == claim['claim_id']:
                record['payload'].update(c['expected_claim'])

    def build(crossed):
        data = copy.deepcopy(db)
        for i, check in enumerate(data['checks']):
            other = 1 - i if crossed else i
            ref = data['requirement_bindings'][other]
            context = authoritative_context(question, manifest, receipt, data['requirement_bindings'], check['task_id'], [ref])
            claim = check['result']['records'][0]
            request = {'protocol_version': 'evidence-check/3', 'check_id': check['check_id'],
                       'investigation_id': check['investigation'], 'dispute_round': 0, 'parent_check_id': None,
                       'prior_relations': [], 'claims': [claim], 'original_context': context,
                       'evidence': [data['records'][0]['payload']]}
            response = {'planning_alignment': {'status': 'complete', 'reason': 'Controlled full partition proposal'},
                        'claims': [{'claim_id': claim['claim_id'], 'answer_alignment': 'answers', 'limitations': [],
                                    'relations': [{'evidence_id': 'ev-original', 'relation': 'supports',
                                                   'quote': 'Synthetic original', 'reason': 'Controlled paragraph',
                                                   'source_alignment': 'qualifies'}]}], 'follow_up_actions': []}
            request_hash, response_hash = native.digest(request), native.digest(response)
            check.update(request=request, request_sha256=request_hash, response_sha256=response_hash)
            check['result']['verification'] = {'protocol_version': 'evidence-check/3', 'model_call_id': 'model-check-' + str(i),
                                             'request_sha256': request_hash, 'response_sha256': response_hash, 'response': response}
            operation = next(o for o in data['operations'] if o['operation_key'] == 'model-check-' + str(i))
            operation['safe_result'] = {'value': response, 'request_binding': {'check_id': check['check_id'],
                                        'request_sha256': request_hash, 'response_sha256': response_hash}}
        for record in data['records']:
            record['payload_sha256'] = native.digest(record['payload'])
        return recount(data)
    return build(crossed)


class ObligationAuditTests(unittest.TestCase):
    def test_adopted_source_must_belong_to_this_check_even_if_exported_in_run(self):
        db = obligation_native_fixture()
        extra = copy.deepcopy(db["records"][0])
        extra["payload"]["evidence_id"] = "ev-other-exported-source"
        extra["payload_sha256"] = native.digest(extra["payload"])
        db["records"].append(extra)
        decision = next(r for r in db["checks"][0]["result"]["records"]
                        if r["record_type"] == "DecisionRecord")
        decision["adopted_evidence_ids"] = ["ev-other-exported-source"]
        for record in db["records"]:
            if record["record_type"] == "DecisionRecord" and record["payload"]["decision_id"] == decision["decision_id"]:
                record["payload"]["adopted_evidence_ids"] = decision["adopted_evidence_ids"]
                record["payload_sha256"] = native.digest(record["payload"])
        proof = native.native_requirements_validation(recount(db))
        self.assertEqual(proof["status"], "invalid", proof)
        self.assertFalse(proof["eligible_for_complete_review"], proof)
        self.assertEqual(proof["issues"], ["NATIVE_CHECK_ADOPTED_SOURCE_MEMBERSHIP_INVALID"])


    def test_initial_native_selector_and_required_wire_fields_are_validated(self):
        for field, value in (("continuation_contract", "agent-frozen-requirements/1"),
                             ("claims_contract", None), ("claims_contract", "foreign"),
                             ("planning_phase", "continuation")):
            with self.subTest(field=field, value=value):
                db = obligation_native_fixture()
                binding = db["operations"][0]["safe_result"]["request_binding"]
                if value is None:
                    binding.pop(field)
                else:
                    binding[field] = value
                proof = native.native_requirements_validation(recount(db))
                self.assertEqual(proof["status"], "invalid", proof)
                self.assertFalse(proof["eligible_for_complete_review"], proof)
        for invalid in ("missing", None, "invalid"):
            with self.subTest(constraints=invalid):
                db = obligation_native_fixture()
                binding = db["operations"][0]["safe_result"]["request_binding"]
                wire = json.loads(binding["planner_declaration"])
                if invalid == "missing":
                    wire.pop("constraints")
                else:
                    wire["constraints"] = invalid
                binding["planner_declaration"] = json.dumps(wire, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
                binding["wire_response_sha256"] = native.digest(wire)
                proof = native.native_requirements_validation(recount(db))
                self.assertEqual(proof["status"], "invalid", proof)
                self.assertFalse(proof["eligible_for_complete_review"], proof)


    def test_reference_cannot_cross_distinct_obligations_with_identical_scope(self):
        correct = native.native_requirements_validation(obligation_native_fixture())
        self.assertEqual(correct["status"], "verified_mapping", correct)
        self.assertTrue(correct["eligible_for_complete_review"], correct)
        crossed = native.native_requirements_validation(obligation_native_fixture(crossed=True))
        self.assertEqual(crossed["status"], "invalid", crossed)
        self.assertFalse(crossed["eligible_for_complete_review"], crossed)
        self.assertIn("NATIVE_CHECK_OBLIGATION_CLAIM_MISMATCH", str(crossed))


class NativeAuditTests(unittest.TestCase):
    def test_two_persisted_requirements_and_current_checks_are_reviewable(self):
        result = native.native_requirements_validation(native_fixture())
        self.assertEqual(result["status"], "verified_mapping", result)
        self.assertTrue(result["mapping_complete"])
        self.assertTrue(result["eligible_for_complete_review"], result)
        self.assertTrue(result["semantic_review_required"])

    def test_current_check_request_result_and_model_receipt_are_cross_bound(self):
        for mutation in (
            "check_id",
            "investigation",
            "claim_text",
            "claim_id",
            "receipt_request",
            "receipt_response",
            "response_body",
            "response_claim",
            "missing_receipt",
            "duplicate_receipt",
            "result_identity",
        ):
            db = native_fixture()
            checked = db["checks"][0]
            receipt_operation = next(
                o for o in db["operations"] if o.get("purpose") == "CHECK"
            )
            receipt = receipt_operation["safe_result"]
            if mutation == "check_id":
                checked["request"]["check_id"] = "other"
            elif mutation == "investigation":
                checked["request"]["investigation_id"] = "other"
            elif mutation == "claim_text":
                checked["request"]["claims"][0]["text"] = "Unrelated claim"
            elif mutation == "claim_id":
                checked["request"]["claims"][0]["claim_id"] = "other"
            elif mutation == "receipt_request":
                receipt["request_binding"]["request_sha256"] = "0" * 64
            elif mutation == "receipt_response":
                receipt["request_binding"]["response_sha256"] = "0" * 64
            elif mutation == "response_body":
                receipt["value"]["claims"][0]["claim_id"] = "other"
            elif mutation == "response_claim":
                receipt["value"]["claims"][0]["claim_id"] = "other"
                checked["response_sha256"] = native.digest(receipt["value"])
                receipt["request_binding"]["response_sha256"] = checked[
                    "response_sha256"
                ]
            elif mutation == "missing_receipt":
                db["operations"].remove(receipt_operation)
            elif mutation == "duplicate_receipt":
                db["operations"].append(copy.deepcopy(receipt_operation))
            elif mutation == "result_identity":
                checked["result"]["check_id"] = "other"
            checked["request_sha256"] = native.digest(checked["request"])
            with self.subTest(mutation=mutation):
                proof = native.validate_saved_audit(
                    native.finalize_audit({"database": recount(db)})
                )
                self.assertEqual(proof["status"], "invalid", proof)
                self.assertFalse(proof["eligible_for_complete_review"], proof)

    def test_malformed_settled_check_receipt_is_saved_invalid_and_review_rejected(self):
        import agent_retest_batch

        for field in ("safe_result", "request_binding"):
            for malformed in (None, [], 17, "invalid", True):
                db = native_fixture()
                operation = next(
                    o for o in db["operations"] if o.get("purpose") == "CHECK"
                )
                if field == "safe_result":
                    operation[field] = malformed
                else:
                    operation["safe_result"][field] = malformed
                with (
                    self.subTest(field=field, malformed=malformed),
                    tempfile.TemporaryDirectory() as directory,
                ):
                    audit = native.finalize_audit({"database": db})
                    path = Path(directory) / "malformed-audit.json"
                    write_private(path, audit)
                    proof = native.validate_saved_audit(json.loads(path.read_text()))
                    self.assertEqual(proof["status"], "invalid", proof)
                    self.assertFalse(proof["eligible_for_complete_review"], proof)
                    self.assertEqual(
                        proof["issues"], ["NATIVE_CHECK_MODEL_RECEIPT_MALFORMED"]
                    )
                    with self.assertRaisesRegex(
                        ValueError, "Native requirement audit incomplete"
                    ):
                        agent_retest_batch.matching_review(
                            {},
                            {
                                "audit_path": str(path),
                                "audit_sha256": file_sha(path),
                                "status": "SUCCEEDED",
                            },
                            "source-digest",
                        )

    def test_question_manifest_receipt_native_mapping_and_source_mismatches_fail_closed(
        self,
    ):
        for mutation in (
            "question",
            "manifest_run",
            "declaration",
            "unsettled",
            "binding_run",
            "criterion",
            "scope",
            "source",
            "source_absent",
            "request",
            "count",
        ):
            db = native_fixture()
            if mutation == "question":
                db["run"][0]["question"] += " more"
            elif mutation == "manifest_run":
                db["requirements"][0]["run_id"] = "other"
            elif mutation == "declaration":
                db["operations"][0]["safe_result"]["value"]["requirements"].pop()
            elif mutation == "unsettled":
                db["operations"][0]["status"] = "UNKNOWN"
            elif mutation == "binding_run":
                db["requirement_bindings"][0]["run_id"] = "other"
            elif mutation == "criterion":
                db["criteria"][0]["criterion_id"] = "other"
            elif mutation == "scope":
                db["criteria"][0]["expected_claim"]["applicability"]["version"][
                    "value"
                ] = "1.0"
                db["criteria"][0]["expected_hash"] = native.digest(
                    db["criteria"][0]["expected_claim"]
                )
            elif mutation == "source":
                db["records"][0]["payload"]["snapshot"]["text"] += "wrong"
                db["records"][0]["payload_sha256"] = native.digest(
                    db["records"][0]["payload"]
                )
            elif mutation == "source_absent":
                db["records"].pop(0)
                recount(db)
            elif mutation == "request":
                db["checks"][0]["request"]["check_id"] = "wrong"
            else:
                db["capture"]["counts"]["criteria"]["rows"] = 0
            with self.subTest(mutation=mutation):
                result = native.native_requirements_validation(db)
                self.assertEqual(result["status"], "invalid", result)
                self.assertFalse(result["eligible_for_complete_review"])

    def test_missing_manifest_missing_binding_and_stale_checks_never_complete(self):
        for mutation in ("manifest", "association", "check"):
            db = native_fixture()
            if mutation == "manifest":
                db["requirements"] = []
                db["requirement_bindings"] = []
            elif mutation == "association":
                db["requirement_bindings"].pop()
            else:
                db["investigation_progress"][0]["current_call_id"] = "new-pending"
            recount(db)
            result = native.native_requirements_validation(db)
            self.assertNotEqual(result["status"], "invalid", result)
            self.assertFalse(result["eligible_for_complete_review"])
        legacy = native.validate_saved_audit({"database": {}})
        self.assertEqual(legacy["status"], "legacy_audit")

    def test_saved_audit_recomputes_native_proof_and_export_digest(self):
        audit = native.finalize_audit({"database": native_fixture()})
        self.assertTrue(
            native.validate_saved_audit(json.loads(json.dumps(audit)))[
                "mapping_complete"
            ]
        )
        audit["database"]["requirements"][0]["declaration_attempt"] = 2
        with self.assertRaisesRegex(ValueError, "BINDING_CHANGED"):
            native.validate_saved_audit(audit)

    def test_outer_question_binding_and_removed_new_version_cannot_downgrade_audit(
        self,
    ):
        db = native_fixture()
        run = db["run"][0]
        audit = native.finalize_audit(
            {
                "database": db,
                "run": {"runId": run["run_id"]},
                "view": {"runId": run["run_id"]},
                "case": {"question": run["question"]},
            }
        )
        self.assertTrue(native.validate_saved_audit(audit)["submitted_request_matches"])
        audit["case"]["question"] = "Different original question"
        with self.assertRaisesRegex(ValueError, "BINDING_CHANGED"):
            native.validate_saved_audit(audit)

    def test_review_gate_rejects_incomplete_native_audit_even_with_claimed_success(
        self,
    ):
        import agent_retest_batch

        db = native_fixture()
        db["requirement_bindings"].pop()
        recount(db)
        audit = native.finalize_audit({"database": db})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "new-audit.json"
            write_private(path, audit)
            with self.assertRaisesRegex(
                ValueError, "Native requirement audit incomplete"
            ):
                agent_retest_batch.matching_review(
                    {},
                    {
                        "audit_path": str(path),
                        "audit_sha256": file_sha(path),
                        "status": "SUCCEEDED",
                    },
                    "source-digest",
                )
        audit = native.finalize_audit({"database": db})
        audit.pop("audit_version")
        with self.assertRaisesRegex(ValueError, "VERSION_MISSING"):
            native.validate_saved_audit(audit)
        audit = native.finalize_audit({"database": native_fixture()})
        audit["native_requirements_validation"]["eligible_for_complete_review"] = False
        with self.assertRaisesRegex(ValueError, "BINDING_CHANGED"):
            native.validate_saved_audit(audit)


if __name__ == "__main__":
    unittest.main()
