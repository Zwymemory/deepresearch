#!/usr/bin/env python3
"""Finite real-model acceptance against an isolated deployment; preparation is default.

Load the four shared research scenarios into the isolated retriever first. Record that
synthetic documents are used; real model execution does not certify real-world truth.
No keys, configuration, live volumes, or deployment are changed by this script.
"""
import argparse
import json
import os
from pathlib import Path
import time
from urllib.parse import urlsplit
from urllib.request import Request,urlopen
from uuid import uuid4

parser=argparse.ArgumentParser()
parser.add_argument("--execute",action="store_true")
parser.add_argument("--base-url",default="http://127.0.0.1:18080")
parser.add_argument("--expected-build-sha",default="pending")
parser.add_argument("--output",type=Path,default=Path("target/agent-round1-real-acceptance.json"))
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
cases=[]
for name in ("empty-retrieval","wrong-material","version-difference","unresolved-conflict"):
    data=json.loads((root/"testdata/agent-foundation/evidence"/(name+".json")).read_text())
    cases.append({"scenario":name,"question":data["question"]})
plan={"expected_build_sha":args.expected_build_sha,"model_execution":"pending","source_profile":"isolated synthetic scenarios",
      "maximum_runs":4,"maximum_model_calls":64,"maximum_tool_calls":64,
      "per_run":{"decisions":8,"models_including_checks_and_retries":16,"tools_including_publication_reads":16,"seconds":180,"input_admission":64000,"output_admission":16384},"cases":cases}
if not args.execute:
    print(json.dumps(plan,ensure_ascii=False,indent=2));raise SystemExit(0)
url=urlsplit(args.base_url)
if url.scheme!="http" or url.hostname not in {"127.0.0.1","localhost"} or url.port!=18080 or url.username or url.password or url.query or url.fragment:
    raise SystemExit("Acceptance uses the isolated loopback service on port 18080")
token=os.environ.get("AGENT_ACCEPTANCE_TOKEN","")
if not token: raise SystemExit("AGENT_ACCEPTANCE_TOKEN is required; never pass a token on the command line")
def request(path,body=None,key=None):
    headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"}
    if key: headers["Idempotency-Key"]=key
    raw=None if body is None else json.dumps(body,ensure_ascii=False).encode()
    with urlopen(Request(args.base_url.rstrip("/")+path,data=raw,headers=headers),timeout=10) as response:
        return json.loads(response.read(150000))
terminal={"SUCCEEDED","INSUFFICIENT_EVIDENCE","FAILED","CANCELLED","TIMED_OUT","BUDGET_EXCEEDED"}
results=[]
for case in cases:
    accepted=request("/api/research/agents",{"question":case["question"],"requestedTools":["kb_search"]},"accept-"+uuid4().hex)
    run=accepted["runId"];deadline=time.monotonic()+195
    while True:
        view=request("/api/research/workflows/"+run)
        if view["status"] in terminal: break
        if time.monotonic()>=deadline:
            request("/api/research/workflows/"+run+"/cancel",{})
            raise SystemExit("Isolated run exceeded its acceptance time window; cancellation requested")
        time.sleep(1)
    results.append({"scenario":case["scenario"],"runId":run,"status":view["status"],"usage":view.get("usage"),
                    "trace":view.get("trace"),"finalResponse":view.get("finalResponse"),"requires_manual_source_and_semantic_review":True})
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({**plan,"model_execution":"executed; manual review pending","results":results},ensure_ascii=False,indent=2))
    print(case["scenario"]+": "+view["status"],flush=True)
