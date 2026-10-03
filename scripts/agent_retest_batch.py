"""Separate, explicitly authorized allowance; the stopped campaign is immutable history."""

from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import time
from uuid import uuid4

from agent_live_common import TERMINAL, file_sha, read_private, write_private

BATCH = "round1-retest-20261003"
POST_IDENTITY_BATCH = "round1-post-identity-20261003"
BATCHES = (BATCH, POST_IDENTITY_BATCH)
POST_IDENTITY_HISTORY_SHA = (
    "de67f8017fa017f96e5e305ba9371673bfe3ca61b878ab3b544dd533eb53eea9"
)
ORDER = [
    "web-only",
    "mixed",
    "version-conditions",
    "contradictory-material",
    "insufficient-evidence",
]


def digest(value):
    return hashlib.sha256(
        json.dumps(
            value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode()
    ).hexdigest()


@contextmanager
def journal_lock(state):
    state = Path(state).resolve()
    if not (state / "run-journal.json").exists():
        raise ValueError("Original campaign journal required; no new state directory")
    with (state / "run-journal.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield read_private(state / "run-journal.json")


def authorize(
    state,
    build_sha,
    source_sha256,
    authority_path,
    historical_ready_path,
    batch_id=BATCH,
):
    """Caller explicitly requests authorization registration, after candidate prerequisites."""
    state = Path(state).resolve()
    if not re.fullmatch(r"[a-f0-9]{40}", build_sha) or not re.fullmatch(
        r"[a-f0-9]{64}", source_sha256
    ):
        raise ValueError("Exact candidate and source manifest required")
    with journal_lock(state) as journal:
        if batch_id not in BATCHES or batch_id in journal.get("authorized_batches", {}):
            raise ValueError(
                "Batch authorization unknown or already exists; never replace it"
            )
        expected_count = 6
        if batch_id == POST_IDENTITY_BATCH:
            prior, prior_rows = validate_history(journal)
            if (
                prior["status"] != "STOPPED"
                or not prior["stop_reason"]
                or len(prior_rows) != 1
                or prior_rows[0].get("status") != "FAILED"
                or file_sha(state / "run-journal.json") != POST_IDENTITY_HISTORY_SHA
            ):
                raise ValueError("Exact seven-row stopped predecessor required")
            expected_count = 7
        elif journal.get("authorized_batches"):
            raise ValueError("Batch authorization already exists; never replace it")
        rows = journal.get("runs", [])
        if len(rows) != expected_count or any(
            row.get("status") not in TERMINAL for row in rows
        ):
            raise ValueError("Expected reconciled historical runs")
        if sum(bool(row.get("retry_of")) for row in rows) != 2:
            raise ValueError("Historical consumed retries differ")
        stopped = read_private(historical_ready_path)
        if not stopped.get("implementation_stopped") or not stopped.get("stop_reason"):
            raise ValueError("Historical stop record required")
        if not Path(authority_path).is_file():
            raise ValueError("Explicit authorization document required")
        snapshot = state / (batch_id + "-history.json")
        if snapshot.exists():
            raise ValueError(
                "Existing history snapshot must be reconciled, never overwritten"
            )
        original_bytes_sha = file_sha(state / "run-journal.json")
        descriptor = os.open(snapshot, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write((state / "run-journal.json").read_bytes())
        batch = {
            "batch_id": batch_id,
            "candidate_sha": build_sha,
            "source_manifest_sha256": source_sha256,
            "maximum_runs": 5,
            "maximum_research_reruns": 0,
            "scenario_order": ORDER,
            "authorized_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "authority_sha256": file_sha(authority_path),
            "authority_path": str(Path(authority_path).resolve()),
            "original_journal_sha256": original_bytes_sha,
            "history_count": len(rows),
            "history_snapshot_path": str(snapshot),
            "history_snapshot_sha256": file_sha(snapshot),
            "historical_stop": {
                "path": str(Path(historical_ready_path).resolve()),
                "sha256": file_sha(historical_ready_path),
                "reason": stopped["stop_reason"],
            },
            "status": "AUTHORIZED",
            "stop_reason": None,
        }
        journal.setdefault("authorized_batches", {})[batch_id] = batch
        write_private(state / "run-journal.json", journal)
        return batch


def validate_history(journal, batch_id=BATCH):
    batches = journal.get("authorized_batches", {})
    allowed = {BATCH} if batch_id == BATCH else set(BATCHES)
    if (
        batch_id not in BATCHES
        or batch_id not in batches
        or set(batches) not in ({BATCH}, set(BATCHES))
        or not allowed <= set(batches)
    ):
        raise ValueError("Explicit authorized batch missing or unexpected batch")
    batch = batches[batch_id]
    if (
        batch.get("batch_id") != batch_id
        or batch["maximum_runs"] != 5
        or batch["maximum_research_reruns"] != 0
        or batch["scenario_order"] != ORDER
    ):
        raise ValueError("Batch limits changed")
    if file_sha(batch["authority_path"]) != batch["authority_sha256"]:
        raise ValueError("Authorization document changed")
    if file_sha(batch["history_snapshot_path"]) != batch["history_snapshot_sha256"]:
        raise ValueError("Historical snapshot changed")
    if batch["history_snapshot_sha256"] != batch["original_journal_sha256"]:
        raise ValueError("Original history byte identity changed")
    stopped = batch["historical_stop"]
    if (
        file_sha(stopped["path"]) != stopped["sha256"]
        or read_private(stopped["path"]).get("stop_reason") != stopped["reason"]
    ):
        raise ValueError("Original stop history changed")
    original = read_private(batch["history_snapshot_path"])
    count = batch["history_count"]
    if (
        count != (6 if batch_id == BATCH else 7)
        or len(original["runs"]) != count
        or journal.get("runs", [])[:count] != original["runs"]
    ):
        raise ValueError("Historical rows changed")
    if {
        k: v for k, v in journal.items() if k not in {"runs", "authorized_batches"}
    } != {k: v for k, v in original.items() if k not in {"runs", "authorized_batches"}}:
        raise ValueError("Historical journal metadata changed")
    if batch_id == POST_IDENTITY_BATCH:
        if (
            set(original.get("authorized_batches", {})) != {BATCH}
            or original["authorized_batches"][BATCH] != batches[BATCH]
            or batch["original_journal_sha256"] != POST_IDENTITY_HISTORY_SHA
        ):
            raise ValueError("Stopped predecessor authorization changed")
        prior, prior_rows = validate_history(journal, BATCH)
        if (
            prior["status"] != "STOPPED"
            or not prior["stop_reason"]
            or len(prior_rows) != 1
        ):
            raise ValueError("Stopped predecessor history changed")
    end = (
        batches[POST_IDENTITY_BATCH]["history_count"]
        if batch_id == BATCH and POST_IDENTITY_BATCH in batches
        else len(journal["runs"])
    )
    if batch_id == BATCH and POST_IDENTITY_BATCH in batches and end != 7:
        raise ValueError("Predecessor boundary changed")
    rows = journal["runs"][count:end]
    if len(rows) > 5 or any(
        row.get("batch_id") != batch_id
        or row.get("scenario") != ORDER[i]
        or row.get("build_sha") != batch["candidate_sha"]
        or row.get("retry_of")
        for i, row in enumerate(rows)
    ):
        raise ValueError("Unexpected or duplicate new reservations")
    return batch, rows


def matching_review(review_state, row, source_sha256, batch_id=BATCH):
    if not review_state.get("ready") or review_state.get("batch_id") != batch_id:
        raise ValueError("B scenario readiness required")
    if review_state.get("source_manifest_sha256") != source_sha256:
        raise ValueError("B reviewed a different source manifest")
    matches = [
        review
        for review in review_state.get("reviews", [])
        if review.get("run_id") == row.get("runId")
    ]
    if len(matches) != 1:
        raise ValueError("Exactly one independent semantic decision required")
    review = matches[0]
    if any(
        review.get(k) != row.get(v)
        for k, v in {
            "scenario": "scenario",
            "build_sha": "build_sha",
            "audit_sha256": "audit_sha256",
        }.items()
    ):
        raise ValueError("Semantic review identity/hash mismatch")
    if review.get("source_hashes") != row.get("source_hashes") or not review.get(
        "reviewed_at"
    ):
        raise ValueError("Semantic review source hashes/time missing or mismatched")
    return review


def admit(journal, scenario, build_sha, review_state, batch_id=BATCH):
    batch, rows = validate_history(journal, batch_id)
    if batch["status"] != "AUTHORIZED" or batch.get("stop_reason"):
        raise ValueError("New batch stopped")
    if build_sha != batch["candidate_sha"]:
        raise ValueError("Only the pinned reviewed candidate is authorized")
    if (
        not review_state.get("ready")
        or review_state.get("batch_id") != batch_id
        or review_state.get("source_manifest_sha256") != batch["source_manifest_sha256"]
    ):
        raise ValueError("B signed-off source readiness required")
    if len(rows) >= 5 or scenario != ORDER[len(rows)]:
        raise ValueError("Ordered five-run allowance only; no duplicate or rerun")
    for row in rows:
        if row.get("status") not in TERMINAL or row.get("validation_error_type"):
            raise ValueError(
                "Prior request unknown, active or failed validation; stop and reconcile"
            )
        if row["scenario"] in ORDER[:3] and row["status"] != "SUCCEEDED":
            raise ValueError("Required complete answer not reached")
        if row["status"] not in {"SUCCEEDED", "INSUFFICIENT_EVIDENCE"}:
            raise ValueError("Infrastructure/budget/terminal failure stops batch")
        if not row.get("audit_path") or file_sha(row["audit_path"]) != row.get(
            "audit_sha256"
        ):
            raise ValueError("Prior audit missing or changed")
        review = matching_review(
            review_state, row, batch["source_manifest_sha256"], batch_id
        )
        if review.get("decision") != "pass":
            raise ValueError("Independent semantic review did not pass")
        if row.get("semantic_review_sha256") and row[
            "semantic_review_sha256"
        ] != digest(review):
            raise ValueError("Prior independent verdict changed")


def reserve(state, scenario, build_sha, peer_sha, review_state, batch_id=BATCH):
    with journal_lock(state) as journal:
        admit(journal, scenario, build_sha, review_state, batch_id)
        batch, rows = validate_history(journal, batch_id)
        for prior in rows:
            review = matching_review(
                review_state, prior, batch["source_manifest_sha256"], batch_id
            )
            prior["semantic_review_sha256"] = digest(review)
        row = {
            "batch_id": batch_id,
            "scenario": scenario,
            "build_sha": build_sha,
            "peer_sha": peer_sha,
            "retry_of": None,
            "fix_description": None,
            "idempotency_key": (
                "live-post-identity-"
                if batch_id == POST_IDENTITY_BATCH
                else "live-retest-"
            )
            + uuid4().hex,
            "status": "REQUEST_RESERVED",
            "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        journal["runs"].append(row)
        write_private(Path(state) / "run-journal.json", journal)
        return row


def update(state, row, batch_id=BATCH):
    with journal_lock(state) as journal:
        batch, rows = validate_history(journal, batch_id)
        matches = [
            item for item in rows if item["idempotency_key"] == row["idempotency_key"]
        ]
        if len(matches) != 1:
            raise ValueError("Reservation not accounted for")
        matches[0].update(row)
        if row.get("validation_error_type") or (
            row.get("status") in TERMINAL
            and (
                row["status"] not in {"SUCCEEDED", "INSUFFICIENT_EVIDENCE"}
                or row["scenario"] in ORDER[:3]
                and row["status"] != "SUCCEEDED"
            )
        ):
            batch.update(status="STOPPED", stop_reason="RUN_FAILED_OR_UNKNOWN")
        write_private(Path(state) / "run-journal.json", journal)


def apply_reviews(state, review_state, batch_id=BATCH):
    with journal_lock(state) as journal:
        batch, rows = validate_history(journal, batch_id)
        for row in rows:
            if not row.get("audit_sha256"):
                continue
            if file_sha(row["audit_path"]) != row["audit_sha256"]:
                raise ValueError("Reviewed audit changed")
            review = matching_review(
                review_state, row, batch["source_manifest_sha256"], batch_id
            )
            if row.get("semantic_review_sha256") and row[
                "semantic_review_sha256"
            ] != digest(review):
                raise ValueError("Immutable semantic decision changed")
            row["semantic_review_sha256"] = digest(review)
            row["semantic_review_decision"] = review.get("decision")
            if review.get("decision") != "pass":
                batch.update(
                    status="STOPPED", stop_reason="SEMANTIC_REVIEW_FAILED_OR_INCOMPLETE"
                )
        if (
            len(rows) == 5
            and batch["status"] == "AUTHORIZED"
            and all(row.get("semantic_review_decision") == "pass" for row in rows)
        ):
            batch["status"] = "COMPLETED"
        write_private(Path(state) / "run-journal.json", journal)
        return batch
