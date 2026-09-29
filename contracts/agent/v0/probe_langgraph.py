"""Read installed pins and exercise a tiny local graph without tools/models/DB."""
from __future__ import annotations

import hashlib
import importlib.metadata
import inspect
import json
import re
import sys
import tomllib
from pathlib import Path
from typing import TypedDict

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, Send

ROOT = Path(__file__).resolve().parents[3]


class ProbeState(TypedDict):
    value: int


def probe():
    project = tomllib.loads((ROOT / "workflow-service/pyproject.toml").read_text())
    packages = {}
    for requirement in project["project"]["dependencies"]:
        name, pin = re.fullmatch(r"([A-Za-z0-9_-]+)(?:\[[^]]+])?==(.+)", requirement).groups()
        installed = importlib.metadata.version(name)
        packages[name] = {"locked": pin, "installed": installed, "matches": installed == pin}
    assert all(row["matches"] for row in packages.values())
    assert sys.version_info[:2] == (3, 12)
    graph = StateGraph(ProbeState)
    graph.add_node("plan", lambda state: {"value": state["value"] + 1})
    graph.add_node("work", lambda state: {"value": state["value"] + 1})
    graph.add_edge(START, "plan")
    graph.add_edge("plan", "work")
    graph.add_edge("work", END)
    compiled = graph.compile(checkpointer=InMemorySaver(), interrupt_before=["work"])
    config = {"configurable": {"thread_id": "offline-foundation-probe"}}
    initial = compiled.invoke({"value": 0}, config)
    pending = compiled.get_state(config)
    assert initial["value"] == 1 and pending.next == ("work",)
    resumed = compiled.invoke(None, config)
    assert resumed["value"] == 2 and compiled.get_state(config).next == ()
    files = ["workflow-service/pyproject.toml", "workflow-service/uv.lock",
             "workflow-service/src/deepresearch_workflow/graph.py", "workflow-service/src/deepresearch_workflow/runner.py"]
    return {"status": "local_library_compatibility_verified", "python": sys.version.split()[0],
        "packages": packages, "source_sha256": {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in files},
        "checks": {"stategraph_compile": True, "in_memory_checkpoint_interrupt_resume": True,
            "send_api_present": callable(Send), "command_api_present": callable(Command),
            "postgres_saver_setup_api": hasattr(PostgresSaver, "setup"),
            "async_postgres_saver_setup_api": hasattr(AsyncPostgresSaver, "setup")},
        "postgres_from_connection_signature": str(inspect.signature(PostgresSaver.from_conn_string)),
        "limits": ["No PostgreSQL connection or new persistence/restart test", "No model/tool calls",
            "Library API compatibility does not implement a dynamic Agent; repository graph remains fixed."],
        "model_calls": 0, "tool_calls": 0, "live_deployment": False}


if __name__ == "__main__":
    target = ROOT / "testdata/agent-foundation/runtime/local-langgraph-compatibility.json"
    target.write_text(json.dumps(probe(), ensure_ascii=False, indent=2) + "\n")
    print(target)
