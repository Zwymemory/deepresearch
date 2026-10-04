"""Actual adapter/accounting/export and strict compatibility, entirely offline."""

import copy
import hashlib
import json
import math
from unittest.mock import AsyncMock

import httpx
import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_identity import decode_object
from deepresearch_workflow.agent_json import JsonDecodeFailure, diagnostic, safe_json_diagnostic
from deepresearch_workflow.agent_model import OpenAIAgentModel, strict_result
from deepresearch_workflow.agent_protocol import AgentRunBudget
from deepresearch_workflow.graph import ModelCallError, WorkflowExecutionError
from deepresearch_workflow.runner import WorkflowRunner

from .test_agent_first_planning import CANARY, PlanningLedger
from .test_agent_identity import REQUEST, export, install_observer
from .test_agent_json_transport import json_settings

BAD = [
    ('{"x":"\\q"}', "syntax", "invalid_escape", "bad_escape"),
    ('{"x":"\\u12XX"}', "syntax", "invalid_unicode_escape", "bad_escape"),
    ('{"x":"a\nb"}', "syntax", "invalid_control_character", "control_character"),
    ('{"x":"open', "syntax", "unterminated_string", "unclosed_string"),
    ('{"x":1,}', "syntax", "expected_property_name", "trailing_comma"),
    ('[1,]', "syntax", "expected_value", "trailing_comma"),
    ('{"x" 1}', "syntax", "expected_colon", "missing_colon"),
    ('{"x":1 "y":2}', "syntax", "expected_comma", "missing_comma"),
    ('{"x":}', "syntax", "expected_value", "missing_value"),
    ('{"x":1', "syntax", "expected_comma", "unclosed_container"),
    ('{} {}', "syntax", "extra_data", "trailing_data"),
    ('```json\n{}\n```', "syntax", "expected_value", "markdown_fence"),
    ('{"秘密😀":1,}', "syntax", "expected_property_name", "trailing_comma"),
    ('{"' + CANARY + '":1,"' + CANARY + '":2}', "duplicate_key", None, None),
    ('{"a":1,"\\u0061":2}', "duplicate_key", None, None),
    ('{"x":NaN}', "nonfinite_constant", None, None),
    ('{"x":Infinity}', "nonfinite_constant", None, None),
    ('{"x":-Infinity}', "nonfinite_constant", None, None),
    ('{"x":1e999}', "nonfinite_float", None, None),
    ('{"x":"\\ud800"}', "decoded_unicode", None, None),
    ('{"\\ud800":0}', "decoded_unicode", None, None),
    ('{"x":"\ud800"}', "content_encoding", None, None),
    ('[' * 1200 + '0' + ']' * 1200, "depth_limit", None, None),
    ('{"x":' + '1' * 4500 + '}', "integer_limit", None, None),
    ('"' + '字' * 22000 + '"', "byte_limit", None, None),
    ('[]', "top_level_shape", None, None),
    ('"' + CANARY + '"', "top_level_shape", None, None),
    ('null', "top_level_shape", None, None),
    ('true', "top_level_shape", None, None),
    ('12', "top_level_shape", None, None),
]


def response(content, mode, *, finish=None):
    message = ({"content": content} if mode == "deepseek_json_object" else {"tool_calls": [
        {"function": {"name": "AgentDecision", "arguments": content}},
    ]})
    return {"model": "deepseek-flash", "choices": [{
        "finish_reason": finish or ("stop" if mode == "deepseek_json_object" else "tool_calls"),
        "message": message,
    }], "usage": {"prompt_tokens": 41, "completion_tokens": 7}}


def gateway(client, ledger, mode):
    settings = json_settings().model_copy(update={"agent_result_transport": mode})
    return AgentBudgetGateway(
        run_id="offline-json", claim_token="offline", model=OpenAIAgentModel(settings, client),
        ledger=ledger, budget=AgentRunBudget(runtime="agent"), guard=AsyncMock(),
    )


@pytest.mark.parametrize("mode", ["deepseek_json_object", "function_call"])
@pytest.mark.parametrize("content,category,code,hint", BAD,
                         ids=[f"{row[1]}-{i}" for i, row in enumerate(BAD)])
async def test_actual_adapter_gateway_export_keeps_safe_subcause_usage_once_and_no_retry(
    content, category, code, hint, mode, caplog,
):
    ledger, calls = PlanningLedger(), []
    raw_response = json.dumps(response(content, mode), ensure_ascii=True).encode()
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda wire: calls.append(wire) or httpx.Response(200, content=raw_response)
    )) as client:
        gw = gateway(client, ledger, mode)
        with pytest.raises(ModelCallError) as error:
            await gw.model_call("model:parse", "DECISION", REQUEST)
        failure = error.value
        row = ledger.rows["model:parse"]
        metadata = row["usage"]["model_failure"]
        info = metadata["json_diagnostic"]
        assert failure.failure_kind == "SCHEMA" and not failure.retryable
        assert info["category"] == category
        assert info["stage"] == ("result_content" if mode == "deepseek_json_object"
                                  else "function_arguments")
        assert info["representation"] == ("text_utf8_surrogatepass"
                                          if category == "content_encoding" else "exact_bytes")
        raw = content.encode("utf-8", errors="surrogatepass")
        assert info["sha256"] == hashlib.sha256(raw).hexdigest()
        assert info["byte_length"] == len(raw)
        if code:
            assert info["decoder_code"] == code and info["structural_hint"] == hint
            assert info["offset_unit"] == "codepoint" and info["offset"] <= len(content)
            assert info["line"] >= 1 and info["column"] >= 1
        assert row["status"] == "UNKNOWN" and row["result"] == {}
        assert row["usage"]["input_tokens"] == 41 and row["usage"]["output_tokens"] == 7
        assert export.safe_model_failure(metadata) == metadata
        WorkflowRunner._log_failure("offline-json", failure)
        assert CANARY not in json.dumps(metadata) + str(failure) + caplog.text
        assert content not in str(failure)
        with pytest.raises(WorkflowExecutionError, match="Cannot replay"):
            await gw.model_call("model:parse", "DECISION", REQUEST)
    assert len(calls) == len(ledger.settlements) == 1


@pytest.mark.parametrize("encoding", ["utf-8", "utf-8-sig", "utf-16", "utf-32"])
def test_shared_decoder_encoding_float_and_unicode_behavior_preserved(encoding):
    raw = '{"x":"中文😀","n":1e999,"s":"\\ud800"}'.encode(encoding)
    value = decode_object(raw, 65536)
    assert value["x"] == "中文😀" and math.isinf(value["n"]) and value["s"] == "\ud800"
    with pytest.raises(JsonDecodeFailure) as error:
        strict_result(raw)
    assert error.value.diagnostic["category"] == "nonfinite_float"
    assert strict_result('{"x":"中文😀","s":"\\ud83d\\ude00"}'.encode(encoding))["s"] == "😀"


@pytest.mark.parametrize("raw,offset", [(b'{"x":"\xff"}', 6), (b'\xef\xbb\xbf{"x":"\xff"}', 9)])
def test_byte_encoding_coordinates_reference_exact_raw_hash(raw, offset):
    with pytest.raises(JsonDecodeFailure) as error:
        decode_object(raw, 65536)
    info = error.value.diagnostic
    assert info["category"] == "byte_encoding" and info["offset_unit"] == "byte"
    assert info["offset"] == offset and info["sha256"] == hashlib.sha256(raw).hexdigest()


@pytest.mark.parametrize("mode", ["deepseek_json_object", "function_call"])
@pytest.mark.parametrize("content", ['{}', '{"x":"中文😀"}', '{"x":"\\u4e2d"}',
                                     '{"x":"\\\"\\\\"}', '{"x":1.2e-3}',
                                     '{"x":' + '[' * 150 + '0' + ']' * 150 + '}'])
async def test_valid_results_and_settled_replay_preserve_original_object(content, mode):
    ledger, calls = PlanningLedger(), []
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda wire: calls.append(wire) or httpx.Response(200, json=response(content, mode))
    )) as client:
        gw = gateway(client, ledger, mode)
        first = await gw.model_call("model:valid", "DECISION", REQUEST)
        replay = await gw.model_call("model:valid", "DECISION", REQUEST)
    assert first.value == replay.value == json.loads(content)
    assert len(calls) == len(ledger.settlements) == 1
    assert ledger.rows["model:valid"]["status"] == "SETTLED"


@pytest.mark.parametrize("mutate", [
    lambda x: x.update(raw=CANARY), lambda x: x.update(category=CANARY),
    lambda x: x.update(version=None), lambda x: x.update(stage=[]),
    lambda x: x.update(byte_length=True), lambda x: x.update(byte_length=-1),
    lambda x: x.update(byte_length=2**63), lambda x: x.update(sha256=CANARY),
    lambda x: x.update(offset=True, offset_unit="byte"),
    lambda x: x.update(offset=999, offset_unit="codepoint", character_length=2),
    lambda x: x.update(line=0, column=1), lambda x: x.update(line=1, column=False),
    lambda x: x.update(finish_reason=CANARY), lambda x: x.update(structural_hint=CANARY),
    lambda x: x.update(decoder_code=CANARY), lambda x: x.update(character_class=CANARY),
])
def test_unknown_diagnostic_metadata_dropped_in_production_and_standalone_export(mutate):
    with pytest.raises(JsonDecodeFailure) as error:
        decode_object(b'{"x":1,}', 65536)
    info = copy.deepcopy(error.value.diagnostic)
    mutate(info)
    assert safe_json_diagnostic(info) is None and export.safe_json_diagnostic(info) is None
    metadata = {"failure_kind": "SCHEMA", "error_class": "result_json", "retryable": False,
                "json_diagnostic": info}
    assert "json_diagnostic" not in export.safe_model_failure(metadata)
    assert CANARY not in json.dumps(export.safe_model_failure(metadata))


async def test_identity_shared_decoder_observer_gateway_and_export_keep_duplicate_subcause(
    tmp_path, monkeypatch,
):
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch, "deepseek-flash")
    raw = ('{"model":"deepseek-flash","' + CANARY + '":1,"' + CANARY + '":2}').encode()
    ledger = PlanningLedger()
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, content=raw)
    )) as client:
        with pytest.raises(ModelCallError) as error:
            await gateway(client, ledger, "deepseek_json_object").model_call(
                "model:identity", "DECISION", REQUEST
            )
    metadata = ledger.rows["model:identity"]["usage"]["model_failure"]
    assert error.value.error_code == "MODEL_IDENTITY_INVALID"
    assert metadata["json_diagnostic"]["category"] == "duplicate_key"
    assert metadata["json_diagnostic"]["stage"] == "identity_response"
    assert export.safe_model_failure(metadata) == metadata
    receipt = json.loads(path.read_text())["receipts"][0]
    assert receipt["json_diagnostic"] == metadata["json_diagnostic"]
    assert CANARY not in json.dumps(receipt) + str(error.value)
    assert len(ledger.settlements) == 1


@pytest.mark.parametrize("value", [None, [], True, "secret"])
def test_type_rejection_and_legacy_receipts_remain_readable(value):
    with pytest.raises(JsonDecodeFailure, match="oversized") as error:
        decode_object(value, 65536)
    assert error.value.diagnostic == diagnostic(None, "input_type")
    legacy = {"failure_kind": "SCHEMA", "error_class": "result_json", "retryable": False}
    assert export.safe_model_failure(legacy) == legacy


@pytest.mark.parametrize("mode", ["deepseek_json_object", "function_call"])
@pytest.mark.parametrize("content", [None, [], 42])
async def test_content_type_rejection_has_no_dynamic_type_or_body(content, mode):
    ledger = PlanningLedger()
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json=response(content, mode))
    )) as client:
        with pytest.raises(ModelCallError):
            await gateway(client, ledger, mode).model_call("model:type", "DECISION", REQUEST)
    metadata = ledger.rows["model:type"]["usage"]["model_failure"]
    assert metadata["json_diagnostic"]["category"] == "input_type"
    assert metadata["json_diagnostic"]["representation"] == "unavailable"
    assert "sha256" not in metadata["json_diagnostic"]
    assert export.safe_model_failure(metadata) == metadata


async def test_empty_content_and_length_finish_remain_distinct_from_syntax():
    for content, finish, category in [(" ", "stop", "empty_content"), ("{", "length", None)]:
        ledger = PlanningLedger()
        async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda _, content=content, finish=finish: httpx.Response(
                200, json=response(content, "deepseek_json_object", finish=finish))
        )) as client:
            with pytest.raises(ModelCallError):
                await gateway(client, ledger, "deepseek_json_object").model_call(
                    "model:finish", "DECISION", REQUEST
                )
        metadata = ledger.rows["model:finish"]["usage"]["model_failure"]
        assert metadata["finish_reason"] == finish
        if category:
            assert metadata["json_diagnostic"]["category"] == category
        else:
            assert metadata["error_class"] == "output_truncated"
            assert "json_diagnostic" not in metadata
        assert export.safe_model_failure(metadata) == metadata


@pytest.mark.parametrize("raw,category", [(b'{"x":NaN}', "nonfinite_constant"),
                                        (b'{"x":1,}', "syntax"), (b'\xff', "byte_encoding")])
async def test_outer_response_decoder_metadata_and_unknown_usage_are_honest(raw, category):
    ledger = PlanningLedger()
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, content=raw)
    )) as client:
        with pytest.raises(ModelCallError):
            await gateway(client, ledger, "deepseek_json_object").model_call(
                "model:outer", "DECISION", REQUEST
            )
    usage = ledger.rows["model:outer"]["usage"]
    assert "input_tokens" not in usage and "output_tokens" not in usage
    metadata = usage["model_failure"]
    assert metadata["json_diagnostic"]["category"] == category
    assert metadata["json_diagnostic"]["stage"] == "response_envelope"
    assert export.safe_model_failure(metadata) == metadata
    assert len(ledger.settlements) == 1


@pytest.mark.parametrize("exception,category", [(MemoryError(CANARY), "memory_limit"),
                                              (RuntimeError(CANARY), "decoder_unknown"),
                                              (json.JSONDecodeError(CANARY, "{}", 1), "syntax")])
def test_decoder_resource_and_unknown_messages_never_export_exception_text(monkeypatch, exception,
                                                                          category):
    def fail(*_, **__):
        raise exception

    monkeypatch.setattr(json, "loads", fail)
    with pytest.raises(JsonDecodeFailure) as error:
        decode_object(b"{}", 65536)
    info = error.value.diagnostic
    assert info["category"] == category and CANARY not in str(info) + str(error.value)
    if category == "syntax":
        assert info["decoder_code"] == "unknown"


def test_exporter_and_production_schema_are_exact_parity():
    import agent_json_diagnostics as standalone

    from deepresearch_workflow import agent_json

    for name in ["VERSION", "STAGES", "CATEGORIES", "DECODER_CODES", "CHARACTERS", "HINTS",
                 "FINISH_REASONS", "MAX_NUMBER"]:
        assert getattr(standalone, name) == getattr(agent_json, name)
    assert standalone.safe_json_diagnostic.__code__.co_code == safe_json_diagnostic.__code__.co_code
