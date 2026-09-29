"""Freeze synthetic inputs/labels; this does not run or score an Agent."""
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = "testdata/agent-foundation/"
SCOPE = dict(tenant_id="fixture-tenant", owner_id="fixture-owner", project_id="fixture-project")
NOW = "2026-09-29T08:00:00Z"


def known(value):
    return dict(status="known", value=value)


def unknown(reason="Synthetic source has no publication date"):
    return dict(status="unknown", value=None, reason=reason)


def record(type_name, **fields):
    return dict(record_type=type_name, schema_version="0.1.0", **SCOPE, **fields)


def applicability(version="1.0"):
    return dict(subject="Synthetic ExampleService request limit", version=known(version),
                valid_at=unknown("No real-world valid time is asserted"), conditions=["Controlled fixture only"])


def base(name, question, actions, verdict):
    external = [dict(record_type="ResearchProject", **SCOPE),
                dict(record_type="Run", run_id="fixture-run", **SCOPE),
                dict(record_type="Task", task_id="fixture-task", run_id="fixture-run", status="done", **SCOPE),
                dict(record_type="Task", task_id="fixture-next-task", run_id="fixture-run", status="pending", **SCOPE),
                dict(record_type="Session", session_id="fixture-old-session", **SCOPE),
                dict(record_type="Session", session_id="fixture-new-session", **SCOPE),
                dict(record_type="Assessment", assessment_id="fixture-label", **SCOPE)]
    return dict(fixture_id=name, fixture_origin="synthetic", implementation_status="expected_only",
                question=question, authorized_scope=SCOPE, records=[], external_refs=external,
                usage=dict(cost=unknown("No model/provider was called")),
                expected_actions=actions, expected_decision=verdict,
                prohibited_inferences=["Do not describe fixture labels as verified Internet facts",
                                       "No autonomous tool action or real memory restart was executed"])


def source(bundle, identity, text, version="1.0"):
    path = BASE + "evidence/sources/" + identity + ".md"
    full = "# 合成来源：" + identity + " 🧪\n\n" + text + "\n"
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(full)
    receipt = "fixture-receipt-" + identity
    row = record("Evidence", evidence_id=identity, version=1, run_id="fixture-run", task_id="fixture-task",
                 receipt_id=receipt,
                 source=dict(source_id="source-" + identity, kind="synthetic_fixture", title="Synthetic " + identity,
                             locator=dict(kind="fixture_file", path=path), version=known(version), published_at=unknown(),
                             observed_at=NOW, source_group=known("group-" + identity), derivation="original",
                             parent_source_id=None, authority="fixture_label"),
                 snapshot=dict(kind="full_text", text=full, sha256=hashlib.sha256(full.encode()).hexdigest(),
                               encoding="utf-8", offset_unit="unicode_codepoint"),
                 applicability=applicability(version), retrieval_score=unknown("No retrieval engine executed"),
                 freshness="fresh", availability="available", validity="unassessed", invalidation_reason=None)
    bundle["records"].append(row)
    metadata_hash = hashlib.sha256(json.dumps(row["source"], ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    bundle["external_refs"].append(dict(record_type="Receipt", receipt_id=receipt, run_id="fixture-run", task_id="fixture-task",
        status="completed", authorized=True, source_bindings=[dict(source_id=row["source"]["source_id"],
        snapshot_sha256=row["snapshot"]["sha256"], source_metadata_sha256=metadata_hash)], **SCOPE))
    return row


def link(evidence, text, relation):
    start = evidence["snapshot"]["text"].index(text)
    return dict(evidence_id=evidence["evidence_id"], relation=relation,
                quote=dict(start=start, end=start + len(text), text=text, sha256=hashlib.sha256(text.encode()).hexdigest()),
                assessment_method="fixture_label", assessment_ref="fixture-label")


def claim_decision(bundle, identity, text, links, status, *, version="1.0", adopted=(), dismissed=(), unresolved=()):
    claim = record("Claim", claim_id=identity, run_id="fixture-run", text=text, kind="factual",
                   applicability=applicability(version), evidence_links=links, decision_status=status, freshness="fresh")
    decision = record("DecisionRecord", decision_id="decision-" + identity, claim_id=identity,
                      run_id="fixture-run", decision_status=status, adopted_evidence_ids=list(adopted),
                      dismissed_evidence=[dict(evidence_id=i, reason=r) for i, r in dismissed],
                      unresolved_evidence_ids=list(unresolved), rationale="Predeclared synthetic fixture label; not runtime semantic adjudication",
                      gaps=["Synthetic research still has an unresolved gap"] if status in ("contested", "insufficient") else [],
                      policy_version="0.1.0", recorded_at=NOW, assessment_method="fixture_label")
    bundle["records"].extend([claim, decision])
    return claim, decision


def packet(bundle):
    ids = lambda kind, field: [x[field] for x in bundle["records"] if x["record_type"] == kind]
    partial = bundle["expected_decision"] in ("contested", "insufficient")
    bundle["records"].append(record("ResearchPacket", packet_id="fixture-packet", run_id="fixture-run",
        task_id="fixture-task", context_id=None, status="partial" if partial else "complete",
        claim_ids=ids("Claim", "claim_id"), evidence_ids=ids("Evidence", "evidence_id"),
        decision_ids=ids("DecisionRecord", "decision_id"), challenge_ids=ids("Challenge", "challenge_id"),
        limitations=["All source texts and labels are fictional"],
        gaps=["Further investigation expected, not executed"] if partial else [], recorded_at=NOW))
    return bundle


def memory(bundle, kind, *, freshness="fresh"):
    progress = dict(goal="Continue synthetic limit research", completed_task_ids=["fixture-task"],
                    pending_task_ids=["fixture-next-task"], excluded_routes=["Empty initial search"],
                    unresolved_claim_ids=[x["claim_id"] for x in bundle["records"] if x["record_type"] == "Claim"],
                    gaps=["Cost measurement remains pending"]) if kind == "research_progress" else None
    result = dict(summary="The controlled version 2.0 source declares a limit of 20 requests",
                  claim_ids=["claim-v2"], decision_ids=["decision-claim-v2"], evidence_ids=["version-v2"],
                  limitations=["Applies only to the controlled 2.0 fixture"]) if kind == "reusable_result" else None
    dependencies = []
    if result:
        for key, entity in (("claim_ids", "Claim"), ("decision_ids", "DecisionRecord"), ("evidence_ids", "Evidence")):
            for identity in result[key]:
                target = next(x for x in bundle["records"] if x.get({"Claim":"claim_id","DecisionRecord":"decision_id","Evidence":"evidence_id"}[entity]) == identity)
                dependencies.append(dict(record_type=entity, record_id=identity, version=target.get("version", 1),
                                         snapshot_sha256=target["snapshot"]["sha256"] if entity == "Evidence" else None))
    row = record("MemoryItem", memory_id="fixture-memory", memory_type=kind, version=1, previous_version=None,
                 lifecycle="active", freshness=freshness, applicability=applicability("2.0" if result else "1.0"),
                 progress=progress, result=result,
                 origin=dict(run_ids=["fixture-run"], session_ids=["fixture-old-session"], packet_ids=["fixture-packet"]),
                 dependencies=dependencies, created_at=NOW, updated_at=NOW,
                 review=dict(reviewed_at=unknown("No real reviewer executed"), due_at=known("2026-10-01T08:00:00Z"), policy="time_bound"),
                 idempotency_key="fixture-memory-create", tombstone=None)
    bundle["records"].append(row)
    return row


def request(operation, version="2.0"):
    return dict(memory_id="fixture-memory", version=1, operation=operation, authorized_scope=SCOPE,
                session_id="fixture-new-session", target_version=known(version), as_of=NOW)


def write(path, data):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def build():
    cases = {}
    empty = base("empty-retrieval", "What is the controlled limit?", ["rewrite authorized search", "stop_with_gaps if still empty"], "insufficient")
    claim_decision(empty, "claim-empty", "A limit cannot be established from empty retrieval", [], "insufficient")
    cases["evidence/empty-retrieval"] = packet(empty)
    wrong = base("wrong-material", "Is the controlled 1.0 limit 99?", ["read_source", "seek_counterevidence", "correct draft or retain uncertainty"], "refuted")
    bad = source(wrong, "wrong-guide", "Version 1.0 allows 99 requests. This controlled guide was deliberately corrupted.")
    good = source(wrong, "controlled-spec", "Version 1.0 allows 10 requests. This is the synthetic oracle for this fixture.")
    claim_decision(wrong, "claim-wrong", "The controlled 1.0 limit is 99", [link(bad, "Version 1.0 allows 99 requests.", "supports"), link(good, "Version 1.0 allows 10 requests.", "refutes")], "refuted",
                   adopted=["controlled-spec"], dismissed=[("wrong-guide", "Predeclared corruption of this synthetic guide")])
    wrong["records"].append(record("Challenge", challenge_id="challenge-wrong", claim_id="claim-wrong", run_id="fixture-run", task_id="fixture-task",
       kind="incorrect_source", status="resolved", evidence_ids=["controlled-spec"], requested_actions=["read_source", "seek_counterevidence"],
       response_decision_id="decision-claim-wrong", remaining_gaps=[], recorded_at=NOW))
    cases["evidence/wrong-material"] = packet(wrong)
    versions = base("version-difference", "Why do version 1.0 and 2.0 list different limits?", ["align versions", "keep both scoped statements"], "supported")
    for version, count, identity in [("1.0", 10, "version-v1"), ("2.0", 20, "version-v2")]:
        text = f"Version {version} allows {count} requests."
        evidence = source(versions, identity, text, version)
        claim_decision(versions, "claim-v" + version[0], text, [link(evidence, text, "supports")], "supported", version=version, adopted=[identity])
    cases["evidence/version-difference"] = packet(versions)
    conflict = base("unresolved-conflict", "What is the same-version controlled limit when peer records disagree?", ["seek independent origin", "retain contested if no adjudicator available"], "contested")
    first = source(conflict, "peer-first", "Version 1.0 allows 10 requests. No authoritative tie-breaker is supplied.")
    second = source(conflict, "peer-second", "Version 1.0 allows 20 requests. No authoritative tie-breaker is supplied.")
    claim_decision(conflict, "claim-conflict", "Version 1.0 allows 10 requests", [link(first, "Version 1.0 allows 10 requests.", "supports"), link(second, "Version 1.0 allows 20 requests.", "refutes")], "contested", unresolved=["peer-first", "peer-second"])
    conflict["records"].append(record("Challenge", challenge_id="challenge-conflict", claim_id="claim-conflict", run_id="fixture-run", task_id="fixture-task",
       kind="direct_conflict", status="unresolved", evidence_ids=["peer-first", "peer-second"], requested_actions=["seek_counterevidence", "stop_with_gaps"],
       response_decision_id="decision-claim-conflict", remaining_gaps=["No independent tie-breaker"], recorded_at=NOW))
    cases["evidence/unresolved-conflict"] = packet(conflict)
    continuation = copy.deepcopy(empty)
    continuation.update(fixture_id="session-continuation", expected_actions=["load authorized project progress", "resume pending cost task in new session"])
    memory(continuation, "research_progress")
    continuation["memory_requests"] = [request("resume", "1.0")]
    cases["memory/session-continuation"] = continuation
    reuse = copy.deepcopy(versions)
    reuse.update(fixture_id="reuse-current-version", expected_actions=["check dependencies", "reuse scoped result with original provenance"])
    memory(reuse, "reusable_result")
    reuse["memory_requests"] = [request("reuse_as_evidence")]
    cases["memory/reuse-current-version"] = reuse
    changed = copy.deepcopy(reuse)
    changed.update(fixture_id="reuse-version-change", expected_actions=["retain old result as lead", "recheck version 3.0 before assertion"])
    changed["records"][-1]["freshness"] = "needs_recheck"
    changed["memory_requests"] = [request("select_as_lead", "3.0")]
    cases["memory/reuse-version-change"] = changed
    invalid = copy.deepcopy(reuse)
    invalid.update(fixture_id="evidence-invalidated", expected_actions=["invalidate dependent result", "reinvestigate before reuse"])
    ev = next(x for x in invalid["records"] if x.get("evidence_id") == "version-v2")
    ev.update(version=2, validity="invalidated", invalidation_reason="Controlled evidence correction notification")
    invalid["records"][-1].update(version=2, previous_version=1, freshness="needs_recheck")
    invalid["memory_requests"] = [dict(request("select_as_lead"), version=2)]
    cases["memory/evidence-invalidated"] = invalid
    deleted = copy.deepcopy(continuation)
    deleted.update(fixture_id="deletion-tombstone", expected_actions=["purge result/index/cache", "deny old checkpoint reinjection"])
    deleted["checkpoint_candidate"] = copy.deepcopy(deleted["records"][-1])
    deleted["records"][-1].update(version=2, previous_version=1, lifecycle="deleted", freshness="superseded", progress=None,
        tombstone=dict(deleted_at=NOW, reason="Controlled user deletion", version=2))
    deleted["memory_requests"] = []
    cases["memory/deletion-tombstone"] = deleted
    isolation = copy.deepcopy(reuse)
    isolation.update(fixture_id="access-isolation", expected_actions=["filter tenant/owner/project before ranking", "deny inaccessible result"])
    isolation["memory_requests"] = []
    cases["memory/access-isolation"] = isolation
    replay = copy.deepcopy(reuse)
    replay.update(fixture_id="idempotent-write", expected_actions=["accept identical operation replay", "reject changed payload or stale revision"])
    replay["memory_writes"] = [dict(memory_id="fixture-memory", expected_version=1, idempotency_key="fixture-operation", payload={"content":"unchanged synthetic candidate"})] * 2
    cases["memory/idempotent-write"] = replay
    for name, bundle in cases.items():
        write(BASE + name + ".json", bundle)
    for area in ("evidence", "memory"):
        write(BASE + area + "/manifest.json", dict(schema_version="0.1.0", fixture_origin="synthetic", implementation_status="expected_only",
             cases=[dict(fixture_id=b["fixture_id"], path=BASE+n+".json") for n,b in cases.items() if n.startswith(area+"/")]))


if __name__ == "__main__":
    build()
