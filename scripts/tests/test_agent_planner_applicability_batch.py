"""Disposable three-original-case allowance; no actual research or history writes."""
import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_live_common as common
import agent_retest_batch as batch
import test_agent_mixed_capacity_batch as predecessor


class PlannerApplicabilityTests(unittest.TestCase):
    def setUp(self):
        self.seed = predecessor.MixedCapacityTests()
        self.seed.setUp()
        self.addCleanup(self.seed.doCleanups)
        self.seed.authorize()
        self.seed.finish(self.seed.reserve('mixed'), 'SUCCEEDED', 'pass')
        self.seed.review['reviews'][0]['report_status'] = 'complete'
        batch.apply_reviews(self.seed.state, self.seed.review, batch.MIXED_CAPACITY_BATCH)
        path = self.seed.state / 'accepted-mixed-review.json'
        common.write_private(path, self.seed.review)
        row = common.read_private(self.seed.state / 'run-journal.json')['runs'][-1]
        self.mixed = {'audit': {'path': row['audit_path'], 'sha256': row['audit_sha256']},
                      'review': {'path': str(path), 'sha256': common.file_sha(path)}}
        self.seed.finish(self.seed.reserve('version-conditions'), 'FAILED', 'incomplete')
        batch.apply_reviews(self.seed.state, self.seed.review, batch.MIXED_CAPACITY_BATCH)
        self.state = self.seed.state
        self.original = common.read_private(self.state / 'run-journal.json')
        self.original_bytes = (self.state / 'run-journal.json').read_bytes()
        for name, value in (
            ('PLANNER_APPLICABILITY_HISTORY_SHA', common.file_sha(self.state / 'run-journal.json')),
            ('ACCEPTED_MIXED', {'candidate_sha': row['build_sha'], 'run_id': row['runId'],
                               'audit_sha256': row['audit_sha256'], 'review_sha256': common.file_sha(path)}),
        ):
            gate = patch.object(batch, name, value)
            gate.start()
            self.addCleanup(gate.stop)
        self.review = copy.deepcopy(self.seed.review)
        self.review.update(batch_id=batch.PLANNER_APPLICABILITY_BATCH, reviews=[])
        self.review['candidate_binding'].update(instruction_policy='agent-obligation-instruction/3',
                                                accepted_mixed=self.mixed)

    def authorize(self):
        old = self.original['authorized_batches'][batch.MIXED_CAPACITY_BATCH]
        return batch.authorize(self.state, 'f' * 40, 'c' * 64, old['authority_path'],
                               old['historical_stop']['path'], batch.PLANNER_APPLICABILITY_BATCH,
                               accepted_web=self.seed.proof, accepted_mixed=self.mixed)

    def reserve(self, scenario, review=None):
        return batch.reserve(self.state, scenario, 'f' * 40, 'a' * 40,
                             self.review if review is None else review, batch.PLANNER_APPLICABILITY_BATCH)

    def finish(self, row, status, decision):
        audit = self.state / ('policy3-' + row['scenario'] + '.json')
        common.write_private(audit, {'fixture': 'synthetic batch receipt'})
        row.update(runId='policy3-' + row['scenario'], status=status, audit_path=str(audit),
                   audit_sha256=common.file_sha(audit), source_hashes={})
        batch.update(self.state, row, batch.PLANNER_APPLICABILITY_BATCH)
        self.review['reviews'].append({'run_id': row['runId'], 'scenario': row['scenario'],
            'build_sha': row['build_sha'], 'audit_sha256': row['audit_sha256'], 'source_hashes': {},
            'decision': decision, 'reviewed_at': 'fixture'})

    def test_three_originals_each_prior_review_and_exact_nineteen_row_prefix(self):
        auth = self.authorize()
        self.assertEqual((auth['history_count'], auth['maximum_runs'], auth['maximum_research_reruns']), (19, 3, 0))
        self.assertEqual(Path(auth['history_snapshot_path']).read_bytes(), self.original_bytes)
        for index, scenario in enumerate(batch.ORDER[2:]):
            row = self.reserve(scenario)
            self.assertTrue(row['idempotency_key'].startswith('live-planner-applicability-'))
            with self.assertRaises(ValueError): self.reserve(scenario)
            self.finish(row, 'SUCCEEDED' if index == 0 else 'INSUFFICIENT_EVIDENCE', 'pass')
            if index < 2:
                with self.assertRaises(ValueError): self.reserve(batch.ORDER[index+3], {**self.review, 'reviews': []})
            batch.apply_reviews(self.state, self.review, batch.PLANNER_APPLICABILITY_BATCH)
        journal = common.read_private(self.state / 'run-journal.json')
        self.assertEqual(journal['runs'][:19], self.original['runs'])
        for name, old in self.original['authorized_batches'].items():
            self.assertEqual(journal['authorized_batches'][name], old)
            batch.validate_history(journal, name)
        self.assertEqual(journal['authorized_batches'][batch.PLANNER_APPLICABILITY_BATCH]['status'], 'COMPLETED')
        with self.assertRaises(ValueError): self.reserve('version-conditions')

    def test_exact_history_policy3_and_both_historical_pass_proofs_required(self):
        with patch.object(batch, 'PLANNER_APPLICABILITY_HISTORY_SHA', '0'*64), self.assertRaises(ValueError): self.authorize()
        with patch.object(batch, 'ACCEPTED_MIXED', {**batch.ACCEPTED_MIXED, 'review_sha256': '0'*64}), self.assertRaises(ValueError): self.authorize()
        self.assertEqual((self.state / 'run-journal.json').read_bytes(), self.original_bytes)
        self.authorize()
        for policy in (None, 'agent-obligation-instruction/1', 'agent-obligation-instruction/2', 'foreign'):
            bad = copy.deepcopy(self.review); bad['candidate_binding']['instruction_policy'] = policy
            with self.assertRaises(ValueError): self.reserve('version-conditions', bad)
        for key in ('accepted_web', 'accepted_mixed'):
            bad = copy.deepcopy(self.review); bad['candidate_binding'].pop(key)
            with self.assertRaises(ValueError): self.reserve('version-conditions', bad)
        for scenario in ('web-only', 'mixed', 'contradictory-material'):
            with self.assertRaises(ValueError): self.reserve(scenario)
        self.assertEqual(len(common.read_private(self.state / 'run-journal.json')['runs']), 19)

    def test_failed_version_and_ambiguous_post_stop_later_paid_slots(self):
        self.authorize()
        row = self.reserve('version-conditions')
        row['validation_error_type'] = 'AmbiguousPostOutcome'
        batch.update(self.state, row, batch.PLANNER_APPLICABILITY_BATCH)
        with self.assertRaises(ValueError): self.reserve('version-conditions')
        self.finish(row, 'FAILED', 'incomplete')
        batch.apply_reviews(self.state, self.review, batch.PLANNER_APPLICABILITY_BATCH)
        with self.assertRaises(ValueError): self.reserve('contradictory-material')
        with self.assertRaises(ValueError): self.authorize()

    def test_illegal_enum_cannot_partially_apply_prior_review(self):
        self.authorize(); self.finish(self.reserve('version-conditions'), 'SUCCEEDED', 'pass')
        batch.apply_reviews(self.state, self.review, batch.PLANNER_APPLICABILITY_BATCH)
        self.finish(self.reserve('contradictory-material'), 'INSUFFICIENT_EVIDENCE', 'complete')
        before = (self.state / 'run-journal.json').read_bytes()
        with self.assertRaises(ValueError): batch.apply_reviews(self.state, self.review, batch.PLANNER_APPLICABILITY_BATCH)
        self.assertEqual((self.state / 'run-journal.json').read_bytes(), before)
