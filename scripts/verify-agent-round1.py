#!/usr/bin/env python3
"""Build a disposable source bundle from A's work and a committed B candidate.

This does not create or merge a Git branch, deploy a service, or read B's worktree edits.
The bundle lives under ignored target/. Specify the full peer SHA in verification records.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

parser=argparse.ArgumentParser()
parser.add_argument("--peer",type=Path,required=True)
parser.add_argument("--peer-sha",required=True)
parser.add_argument("--python",required=True)
parser.add_argument("--java-tests",default="DifyContextInputsTest,WorkflowServiceTest,WorkflowAccessServiceTest")
parser.add_argument("--integration-tests",default="AgentRuntimePostgresIT")
parser.add_argument("--prepare-only",action="store_true")
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]

def git(where,*arguments):
    return subprocess.check_output(["git","-C",str(where),*arguments])

sha=git(args.peer,"rev-parse",args.peer_sha+"^{commit}").decode().strip()
if sha!=args.peer_sha: raise SystemExit("Use the full committed peer SHA")
(root/"target").mkdir(exist_ok=True)
bundle=Path(tempfile.mkdtemp(prefix="agent-round1-",dir=root/"target"))
paths=git(root,"ls-files","-z").decode().split("\0")
for path in filter(None,paths):
    if path.startswith("docs/interview/") or path==".env": continue
    source=root/path
    if source.is_file():
        destination=bundle/path;destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source,destination)
owned=("src/main/java/com/deepresearch/evidence/","src/test/java/com/deepresearch/evidence/",
       "src/main/resources/db/migration/V18__agent_evidence.sql","workflow-service/src/deepresearch_workflow/evidence_check.py",
       "src/main/resources/db/migration/V20__",
       "workflow-service/tests/test_evidence_check.py","testdata/agent-round1/evidence/")
owned=(*owned,"workflow-service/tests/test_evidence_repair.py","testdata/agent-round1-repair/evidence/")
for path in filter(None,git(args.peer,"ls-tree","-rz","--name-only",sha).decode().split("\0")):
    if not any(path.startswith(prefix) for prefix in owned): continue
    destination=bundle/path;destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_bytes(git(args.peer,"show",sha+":"+path))
(bundle/"PEER_SHA.txt").write_text(sha+"\n")
print("source_bundle="+str(bundle),flush=True)
print("peer_sha="+sha,flush=True)
if args.prepare_only: raise SystemExit(0)
environment={**os.environ,"AGENT_PYTHON":args.python,"PYTHONPATH":str(bundle/"workflow-service/src"),"PYTHONDONTWRITEBYTECODE":"1"}
subprocess.run([args.python,"-B","-m","pytest","tests/test_agent_runtime.py","tests/test_agent_investigations.py","tests/test_agent_model.py","tests/test_evidence_client.py","tests/test_evidence_check.py","tests/test_evidence_repair.py",
                "tests/test_runner.py","tests/test_domain.py"],cwd=bundle/"workflow-service",env=environment,check=True)
subprocess.run(["mvn","-q","-Pintegration","-DskipTests=false","-Dtest="+args.java_tests,
                "-Dit.test="+args.integration_tests,"verify"],cwd=bundle,env=environment,check=True)
