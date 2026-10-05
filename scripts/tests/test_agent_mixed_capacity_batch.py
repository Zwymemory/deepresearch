"""Disposable ordered allowance and actual verifier entrance; never real research."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_retest_batch as batch
import agent_live_common as common
import test_agent_remaining_four_batch as predecessor


class MixedCapacityTests(unittest.TestCase):
    def setUp(self):
        self.seed = predecessor.RemainingFourTests()
        self.seed.setUp()
        self.addCleanup(self.seed.doCleanups)
        self.seed.authorize()
        self.seed.finish(self.seed.reserve('mixed'), 'INSUFFICIENT_EVIDENCE', 'incomplete')
        batch.apply_reviews(self.seed.state, self.seed.review, batch.REMAINING_FOUR_BATCH)
        self.state, self.proof = self.seed.state, self.seed.proof
        self.original = common.read_private(self.state / 'run-journal.json')
        self.original_bytes = (self.state / 'run-journal.json').read_bytes()
        gate = patch.object(batch, 'MIXED_CAPACITY_HISTORY_SHA', common.file_sha(self.state / 'run-journal.json'))
        gate.start(); self.addCleanup(gate.stop)
        self.review = copy.deepcopy(self.seed.review)
        self.review.update(batch_id=batch.MIXED_CAPACITY_BATCH, reviews=[])
        self.review['candidate_binding']['instruction_policy'] = 'agent-obligation-instruction/2'

    def authorize(self):
        return batch.authorize(self.state, 'f' * 40, 'c' * 64, self.seed.seed.seed.authority,
                               self.seed.seed.seed.old_ready, batch.MIXED_CAPACITY_BATCH,
                               accepted_web=self.proof)

    def reserve(self, scenario, review=None):
        return batch.reserve(self.state, scenario, 'f' * 40, 'a' * 40,
                             self.review if review is None else review, batch.MIXED_CAPACITY_BATCH)

    def finish(self, row, status, decision):
        audit = self.state / ('new-' + row['scenario'] + '.json')
        common.write_private(audit, {'fixture': 'offline policy only'})
        row.update(runId='new-' + row['scenario'], status=status, audit_path=str(audit),
                   audit_sha256=common.file_sha(audit), source_hashes={})
        batch.update(self.state, row, batch.MIXED_CAPACITY_BATCH)
        self.review['reviews'].append({'run_id':row['runId'],'scenario':row['scenario'],
            'build_sha':row['build_sha'],'audit_sha256':row['audit_sha256'],'source_hashes':{},
            'decision':decision,'reviewed_at':'fixture'})

    def test_four_original_cases_order_review_and_seventeen_row_prefix(self):
        auth = self.authorize()
        self.assertEqual((auth['history_count'], auth['maximum_runs'], auth['maximum_research_reruns']), (17,4,0))
        self.assertEqual(Path(auth['history_snapshot_path']).read_bytes(), self.original_bytes)
        for i, scenario in enumerate(batch.ORDER[1:]):
            row = self.reserve(scenario)
            self.assertTrue(row['idempotency_key'].startswith('live-mixed-capacity-'))
            with self.assertRaises(ValueError): self.reserve(scenario)
            self.finish(row, 'SUCCEEDED' if i < 2 else 'INSUFFICIENT_EVIDENCE', 'pass')
            if i < 3:
                with self.assertRaises(ValueError): self.reserve(batch.ORDER[i+2], {**self.review, 'reviews': []})
            batch.apply_reviews(self.state, self.review, batch.MIXED_CAPACITY_BATCH)
        journal = common.read_private(self.state / 'run-journal.json')
        self.assertEqual(journal['runs'][:17], self.original['runs'])
        for name in batch.BATCHES[:11]:
            self.assertEqual(journal['authorized_batches'][name], self.original['authorized_batches'][name])
            batch.validate_history(journal, name)
        self.assertEqual(journal['authorized_batches'][batch.MIXED_CAPACITY_BATCH]['status'], 'COMPLETED')
        with self.assertRaises(ValueError): self.reserve('mixed')

    def test_exact_predecessor_and_policy2_required_before_reservation(self):
        with patch.object(batch, 'MIXED_CAPACITY_HISTORY_SHA', '0'*64), self.assertRaises(ValueError): self.authorize()
        self.assertEqual((self.state/'run-journal.json').read_bytes(), self.original_bytes)
        self.authorize()
        for policy in (None, 'agent-obligation-instruction/1', 'foreign'):
            bad = copy.deepcopy(self.review); bad['candidate_binding']['instruction_policy'] = policy
            with self.assertRaises(ValueError): self.reserve('mixed', bad)
        self.assertEqual(len(common.read_private(self.state/'run-journal.json')['runs']),17)
        with self.assertRaises(ValueError): self.reserve('web-only')

    def test_incomplete_mixed_stops_without_later_slot_or_reauthorization(self):
        self.authorize(); self.finish(self.reserve('mixed'),'INSUFFICIENT_EVIDENCE','incomplete')
        batch.apply_reviews(self.state,self.review,batch.MIXED_CAPACITY_BATCH)
        with self.assertRaises(ValueError): self.reserve('version-conditions')
        with self.assertRaises(ValueError): self.authorize()

    def test_unknown_post_and_illegal_enum_never_reissue_or_partially_apply(self):
        self.authorize(); row=self.reserve('mixed'); row['validation_error_type']='AmbiguousPostOutcome'
        batch.update(self.state,row,batch.MIXED_CAPACITY_BATCH)
        with self.assertRaises(ValueError): self.reserve('mixed')
        self.finish(row,'SUCCEEDED','complete')
        before=(self.state/'run-journal.json').read_bytes()
        with self.assertRaises(ValueError): batch.apply_reviews(self.state,self.review,batch.MIXED_CAPACITY_BATCH)
        self.assertEqual((self.state/'run-journal.json').read_bytes(),before)


class ActualRuntimeEntranceTests(unittest.TestCase):
    def test_real_verify_runtime_accepts_policy1_2_and_rejects_unknown_or_changed_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); source=root/'source'; source.mkdir()
            jar=root/'service.jar'; jar.write_bytes(b'offline-jar')
            manifest=root/'manifest.json'; common.write_private(manifest, {'blobs':{}})
            modules={}
            for name in ('agent_decision_instruction','agent_schema_diagnostics','agent_runtime','agent_budget'):
                path=source/(name+'.py'); path.write_text('# controlled committed module\n')
                modules[name]={'path':str(path),'sha256':common.file_sha(path)}
            identity_path=root/'identity.json'
            decision={'instruction_policy':'agent-obligation-instruction/2','schema_diagnostic_version':'agent-schema-diagnostic/1','modules':modules,
                **{k:'b'*64 for k in ('instruction_builder_sha256','failure_classifier_sha256','initial_instruction_sha256','continuation_instruction_sha256')}}
            ready={'ready':True,'formal_services_unchanged':True,'app_base_url':'http://127.0.0.1:18080',
                'build_sha':'a'*40,'jar_path':str(jar),'jar_sha256':common.file_sha(jar),
                'app_container':'owned-app','app_image_id':'image','isolation_id':'fixture-owner',
                'source_manifest_path':str(manifest),'source_manifest_sha256':common.file_sha(manifest),
                'source_archive':str(source),'sidecar_identity_path':str(identity_path),'sidecar_pid':123,
                'sidecar_source_sha256':common.source_digest(source),'sidecar_base_url':'http://127.0.0.1:18090',
                'database_container':'owned-pg','database_port':15432,'database_volume':'owned-volume','decision_identity':decision}
            identity={'fixtures':False,'model_class':'OpenAIAgentModel','pid':123,'source_dir':str(source),'decision_identity':decision}
            build={'revision':ready['build_sha'],'source-manifest-sha256':ready['source_manifest_sha256'],'isolation-id':ready['isolation_id']}
            app={'Config':{'Labels':{'deepresearch.validation.owner':'fixture-owner'}},'Image':'image','NetworkSettings':{'Ports':{'18080/tcp':[{'HostIp':'127.0.0.1','HostPort':'18080'}]}}}
            pg={'Config':{'Labels':{'deepresearch.validation.owner':'fixture-owner'}},'NetworkSettings':{'Ports':{'5432/tcp':[{'HostIp':'127.0.0.1','HostPort':'15432'}]}},'Mounts':[{'Name':'owned-volume'}]}
            def process(argv, **kwargs):
                if argv[:2]==['docker','inspect']: return json.dumps([app if argv[2]=='owned-app' else pg]).encode()
                if argv[:2]==['docker','exec']: return ready['jar_sha256']+'  /app/deepresearch.jar'
                self.fail('Unexpected process')
            def http(base,path,token=None):
                return {'build':build} if path=='/actuator/info' else {'status':'UP','runner':'enabled'}
            with patch.object(common.subprocess,'check_output',side_effect=process),patch.object(common,'http_json',side_effect=http),patch.object(common,'assert_process'):
                for policy in ('agent-obligation-instruction/1','agent-obligation-instruction/2','agent-obligation-instruction/3'):
                    decision['instruction_policy']=policy;common.write_private(identity_path,identity)
                    self.assertTrue(common.verify_runtime(ready,'fixture')['verified'])
                decision['instruction_policy']='foreign';common.write_private(identity_path,identity)
                with self.assertRaisesRegex(ValueError,'policy proof'):common.verify_runtime(ready,'fixture')
                decision['instruction_policy']='agent-obligation-instruction/2';common.write_private(identity_path,identity)
                changed=copy.deepcopy(ready);changed['decision_identity']['initial_instruction_sha256']='c'*64
                with self.assertRaisesRegex(ValueError,'differs'):common.verify_runtime(changed,'fixture')
                modules['agent_runtime']['sha256']='0'*64;common.write_private(identity_path,identity)
                with self.assertRaisesRegex(ValueError,'module changed'):common.verify_runtime(ready,'fixture')
