"""Read-only installed Dify schema/template check; never invoke a workflow/provider."""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def probe(container):
    dsl_path = ROOT / "integrations/dify/deepresearch-evidence-v1.yml"
    dsl = json.loads(dsl_path.read_text())
    nodes = {node["id"]: node for node in dsl["workflow"]["graph"]["nodes"]}
    inputs = {"start": nodes["start"]["data"], "template": nodes["planner"]["data"]["prompt_template"][1]["text"]}
    program = '''import hashlib,importlib.metadata,importlib.util,json,sys
from pathlib import Path
from graphon.nodes.start.entities import StartNodeData
from graphon.runtime import VariablePool
from graphon.variables.template_resolution import convert_template
data=json.loads(sys.stdin.read())
validated=StartNodeData.model_validate(data['start'])
context={'schema_version':'0.1.0','trust':'untrusted_context_not_evidence','session_summary':'old progress','recent_conversation':['user: synthetic continuation'],'memories':['synthetic unverified lead']}
pool=VariablePool()
for key,value in {'question':'current synthetic question','allowed_tools':'kb_search','session_summary':json.dumps(context)}.items(): pool.add(('start',key),value)
rendered=convert_template(pool,data['template']).text
assert 'synthetic continuation' in rendered and 'synthetic unverified lead' in rendered
assert 'current synthetic question' in rendered and 'Allowed tools: kb_search' in rendered
empty=VariablePool()
empty.add(('start','session_summary'),'')
assert convert_template(empty,'x{{#start.session_summary#}}y').text=='xy'
omitted=convert_template(VariablePool(),'{{#start.new_optional_field#}}').text
paths=[Path(importlib.util.find_spec('graphon.nodes.start.start_node').origin),Path('/app/api/core/workflow/nodes/agent/agent_node.py')]
hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()}
print(json.dumps({'status':'installed_start_schema_and_template_verified','graphon_version':importlib.metadata.version('graphon'),'start_variables':[v.variable for v in validated.variables],'envelope_reaches_rendered_prompt':True,'empty_existing_input_compatible':True,'omitted_new_field_rendering':omitted,'classic_agent_source_present':paths[1].is_file(),'installed_source_sha256':hashes,'model_calls':0,'workflow_calls':0,'limits':['No full workflow compile/import/publish or real model consumption','Agent source presence does not verify strategy plugin or tool execution']}))
'''
    command = ["docker", "exec", "-i", container, "/app/api/.venv/bin/python", "-B", "-c", program]
    result = json.loads(subprocess.check_output(command, input=json.dumps(inputs).encode(), cwd=ROOT))
    result["container"] = container
    result["image_id"] = subprocess.check_output(["docker", "inspect", "--format", "{{.Image}}", container], text=True).strip()
    result["image_tag"] = subprocess.check_output(["docker", "inspect", "--format", "{{.Config.Image}}", container], text=True).strip()
    result["local_candidate_dsl_sha256"] = hashlib.sha256(dsl_path.read_bytes()).hexdigest()
    result["reference_published_dsl_sha256"] = "87a3e241456c34dbf5dcb772fd0667b08e527650f586ee097411676b0fc05033"
    result["live_deployment"] = False
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="dify-local-api-1")
    args = parser.parse_args()
    target = ROOT / "testdata/agent-foundation/runtime/local-dify-compatibility.json"
    target.write_text(json.dumps(probe(args.container), ensure_ascii=False, indent=2) + "\n")
    print(target)
