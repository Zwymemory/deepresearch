"""Disposable acceptance-policy fixtures; no research, provider or actual DB IO."""
import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_retest_batch as batch
from agent_live_common import file_sha, read_private, write_private
import test_agent_decision_web_batch as predecessor


class RemainingFourTests(unittest.TestCase):
    def setUp(self):
        seed = predecessor.DecisionWebTests()
        seed.setUp()
        self.addCleanup(seed.doCleanups)
        seed.authorize()
        self.seed, self.state = seed, seed.state
        row = seed.reserve()
        audit = self.state / 'accepted-web-audit.json'
        write_private(audit, {'fixture': 'unversioned immutable history policy only'})
        row.update(runId='accepted-web-fixture', status='SUCCEEDED', audit_path=str(audit),
                   audit_sha256=file_sha(audit), source_hashes={})
        batch.update(self.state, row, batch.DECISION_CONTRACT_WEB_BATCH)
        entry = {'run_id': row['runId'], 'scenario': 'web-only', 'build_sha': row['build_sha'],
                 'audit_sha256': row['audit_sha256'], 'source_hashes': {},
                 'decision': 'complete', 'reviewed_at': 'fixture-time'}
        review_path = self.state / 'original-complete-token.json'
        write_private(review_path, {'reviews': [entry]})
        correction = {'decision': 'pass', 'candidate_sha': row['build_sha'],
                      'run_id': row['runId'], 'audit_sha256': row['audit_sha256'],
                      'original_C_verdict_sha256': file_sha(review_path),
                      'original_applied_decision_token': 'complete',
                      'original_applied_canonical_review_digest': batch.digest(entry)}
        correction_path = self.state / 'separate-pass-clarification.json'
        write_private(correction_path, correction)
        # Build already-applied historical administrative state, without reapplying it.
        j = read_private(self.state / 'run-journal.json')
        j['runs'][-1].update(semantic_review_decision='complete', semantic_review_sha256=batch.digest(entry))
        j['authorized_batches'][batch.DECISION_CONTRACT_WEB_BATCH].update(
            status='STOPPED', stop_reason='SEMANTIC_REVIEW_FAILED_OR_INCOMPLETE')
        write_private(self.state / 'run-journal.json', j)
        self.original, self.original_bytes = j, (self.state / 'run-journal.json').read_bytes()
        self.proof = {name: {'path': str(p), 'sha256': file_sha(p)}
                      for name, p in [('audit', audit), ('review', review_path), ('clarification', correction_path)]}
        accepted = {'candidate_sha': row['build_sha'], 'run_id': row['runId'],
                    **{name + '_sha256': ref['sha256'] for name, ref in self.proof.items()}}
        for name, value in [('ACCEPTED_WEB', accepted), ('REMAINING_FOUR_HISTORY_SHA', file_sha(self.state / 'run-journal.json'))]:
            gate = patch.object(batch, name, value)
            gate.start()
            self.addCleanup(gate.stop)
        self.review = copy.deepcopy(seed.review)
        self.review.update(batch_id=batch.REMAINING_FOUR_BATCH, reviews=[])
        self.review['candidate_binding']['accepted_web'] = copy.deepcopy(self.proof)

    def authorize(self, proof=None):
        return batch.authorize(self.state, 'f' * 40, 'c' * 64, self.seed.seed.authority,
                               self.seed.seed.old_ready, batch.REMAINING_FOUR_BATCH,
                               accepted_web=self.proof if proof is None else proof)

    def reserve(self, scenario, review=None):
        return batch.reserve(self.state, scenario, 'f' * 40, 'a' * 40,
                             self.review if review is None else review, batch.REMAINING_FOUR_BATCH)

    def finish(self, row, status, decision='pass'):
        audit = self.state / (row['scenario'] + '-audit.json')
        write_private(audit, {'fixture': 'unversioned policy test only'})
        row.update(runId=row['scenario'] + '-fixture', status=status, audit_path=str(audit),
                   audit_sha256=file_sha(audit), source_hashes={})
        batch.update(self.state, row, batch.REMAINING_FOUR_BATCH)
        entry = {'run_id': row['runId'], 'scenario': row['scenario'], 'build_sha': row['build_sha'],
                 'audit_sha256': row['audit_sha256'], 'source_hashes': {},
                 'decision': decision, 'reviewed_at': 'fixture-time'}
        self.review['reviews'].append(entry)
        return entry

    def test_four_ordered_original_slots_require_each_prior_review_and_preserve_history(self):
        auth = self.authorize()
        self.assertEqual((auth['history_count'], auth['maximum_runs'], auth['maximum_research_reruns']), (16, 4, 0))
        self.assertEqual(Path(auth['history_snapshot_path']).read_bytes(), self.original_bytes)
        for i, scenario in enumerate(batch.ORDER[1:]):
            with self.assertRaises(ValueError):
                self.reserve('web-only')
            row = self.reserve(scenario)
            self.assertTrue(row['idempotency_key'].startswith('live-remaining-four-'))
            with self.assertRaises(ValueError):
                self.reserve(scenario)
            self.finish(row, 'SUCCEEDED' if i < 2 else 'INSUFFICIENT_EVIDENCE')
            if i < 3:
                missing = copy.deepcopy(self.review)
                missing['reviews'].pop()
                with self.assertRaises(ValueError):
                    self.reserve(batch.ORDER[i + 2], missing)
            batch.apply_reviews(self.state, self.review, batch.REMAINING_FOUR_BATCH)
        j = read_private(self.state / 'run-journal.json')
        self.assertEqual(j['authorized_batches'][batch.REMAINING_FOUR_BATCH]['status'], 'COMPLETED')
        self.assertEqual(j['runs'][:16], self.original['runs'])
        for name in batch.BATCHES[:10]:
            self.assertEqual(j['authorized_batches'][name], self.original['authorized_batches'][name])
            batch.validate_history(j, name)
        with self.assertRaises(ValueError):
            self.reserve('mixed')

    def test_exact_history_and_accepted_web_correction_are_mandatory(self):
        for bad in ({}, {**self.proof, 'audit': {**self.proof['audit'], 'sha256': '0' * 64}}):
            with self.assertRaises(ValueError):
                self.authorize(bad)
        with patch.object(batch, 'REMAINING_FOUR_HISTORY_SHA', '0' * 64), self.assertRaises(ValueError):
            self.authorize()
        self.assertEqual((self.state / 'run-journal.json').read_bytes(), self.original_bytes)
        j = copy.deepcopy(self.original)
        j['runs'][-1]['status'] = 'FAILED'
        with self.assertRaises(ValueError):
            batch.validate_accepted_web(j, self.proof)
        self.authorize()
        bad = copy.deepcopy(self.review)
        bad['candidate_binding'].pop('accepted_web')
        with self.assertRaises(ValueError):
            self.reserve('mixed', bad)
        for field in ('instruction_policy', 'schema_diagnostic_version', 'verifier_protocol'):
            bad = copy.deepcopy(self.review)
            bad['candidate_binding'].pop(field)
            with self.assertRaises(ValueError):
                self.reserve('mixed', bad)

    def test_invalid_report_status_enum_is_rejected_before_any_journal_write(self):
        self.authorize()
        entry = self.finish(self.reserve('mixed'), 'SUCCEEDED', 'complete')
        before = (self.state / 'run-journal.json').read_bytes()
        for invalid in ('complete', 'approved', None, []):
            entry['decision'] = invalid
            with self.assertRaises(ValueError):
                batch.apply_reviews(self.state, self.review, batch.REMAINING_FOUR_BATCH)
            self.assertEqual((self.state / 'run-journal.json').read_bytes(), before)
        entry['decision'] = 'pass'
        batch.apply_reviews(self.state, self.review, batch.REMAINING_FOUR_BATCH)
        self.reserve('version-conditions')

    def test_answerable_insufficiency_or_unknown_post_stops_remaining_slots(self):
        self.authorize()
        self.finish(self.reserve('mixed'), 'INSUFFICIENT_EVIDENCE')
        j = read_private(self.state / 'run-journal.json')
        self.assertEqual(j['authorized_batches'][batch.REMAINING_FOUR_BATCH]['status'], 'STOPPED')
        with self.assertRaises(ValueError):
            self.reserve('version-conditions')

    def test_ambiguous_post_cannot_reserve_or_reopen_a_second_slot(self):
        self.authorize()
        row = self.reserve('mixed')
        row['validation_error_type'] = 'AmbiguousPostOutcome'
        batch.update(self.state, row, batch.REMAINING_FOUR_BATCH)
        with self.assertRaises(ValueError):
            self.reserve('mixed')
        with self.assertRaises(ValueError):
            self.authorize()
