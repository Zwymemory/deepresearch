# Strict JSON diagnostics

`agent-json-diagnostic/1` describes a rejection without retaining response text,
object keys, snippets, exception messages, stack traces or model repair attempts.
It adds observability, not a new result transport or relaxed JSON grammar.
The actual rejected response from run `wf-ad196869-8d9c-41fb-ae1d-db0133131afb`
was not retained. Its specific parsing cause remains unknown.

The nested `json_diagnostic` accompanies a model failure in its UNKNOWN usage
receipt and standalone safe audit. Public failure text includes only the fixed
category and decoder code; the runner log can include the complete sanitized
diagnostic. Unknown fields, invalid enum values, booleans used as counts,
out-of-bound numbers and inconsistent coordinates discard the nested diagnostic.
Old receipts without this field remain readable. Known measured usage is still
settled once; an UNKNOWN operation cannot trigger a second provider call.

## Fields and hash domains

Required fields are `version`, `stage`, `category`, and `representation`.

| Field | Meaning |
| --- | --- |
| `stage` | Fixed decode, response envelope, result content, function arguments, identity request or identity response boundary. |
| `category` | Fixed encoding, syntax, duplicate key, nonfinite constant, overflowed float, decoded Unicode, shape, empty content, byte/type/depth/integer/memory resource or unknown decoder rejection. |
| `representation=exact_bytes` | SHA256 and byte length refer to the exact original bytes, including a BOM where present. Result strings that encoded successfully use their exact UTF8 bytes. |
| `representation=text_utf8_surrogatepass` | SHA256 and byte length refer to the original string encoded as UTF8 with surrogatepass. This explicitly declared domain handles unpaired surrogates without lossy replacement. Character length counts Python codepoints. |
| `representation=unavailable` | Wrong input type; no body/hash/length or arbitrary dynamic type name is recorded. |
| `offset`, `offset_unit` | Syntax positions count codepoints in the decoder's decoded document; encoding positions count original raw bytes, including BOM adjustment when decoding sliced a prefix. No snippet is retained. |
| `line`, `column` | One-based Python JSON decoder coordinates, present for syntax errors only. They are not byte offsets. |
| `decoder_code` | Fixed mapping of known Python JSONDecodeError messages; unknown messages produce `unknown`, never their text. |
| `character_class`, `previous_character_class` | Structural token classes or letter/number/whitespace/other/end, not actual characters. Previous skips JSON whitespace. |
| `structural_hint` | Fixed observation such as markdown fence, trailing comma, missing colon/comma/value, bad escape, unclosed string, container at EOF or trailing data. This is not semantic or provider-root-cause proof. |
| `finish_reason` | Only stop, length, tool_calls, function_call or content_filter. It remains distinct from the parsing category. Unknown provider values are not exported. |
| `top_level_type` | Fixed array/string/number/boolean/null class when an otherwise parsed result is not an object. |

Syntax decoder codes distinguish expected value/property name/colon/comma,
extra data, unterminated string, invalid escape/Unicode escape/control character.
Positions, lengths and hashes provide evidence for later comparison without
recovering the original output. Duplicate keys and nonfinite constants do not
have trustworthy source positions from object-pairs/constant hooks, so none are
invented. Escaped keys that decode to the same name are still duplicates.

## Compatibility and limits

The shared identity decoder preserves `json.loads(bytes)` encoding detection,
including UTF8 BOM, UTF16 and UTF32, and its existing acceptance of overflowed
floats and escaped unpaired surrogates. The result parser still rejects those
float/Unicode values during its original recursive post-check. Nonfinite
constants and duplicate keys remain rejected in the shared decoder. Primitive
top-level JSON can be decoded, but the result adapter still requires an object.

No new depth cap or interpreter setting is introduced. Existing Python recursion
and integer conversion limits are reported as depth/integer resource rejections.
The existing 65536-byte function/result and bounded envelope limits remain in
force. No UTF8 replacement, code-fence stripping, JSON5, trailing-comma cleanup,
duplicate-key last-wins, NaN coercion, model repair call or retry is added.
Request endpoint, prompts, schema, model identity policy, transport identity and
budgets are unchanged. A length finish reason remains `output_truncated` and is
not reclassified as malformed JSON.

DeepSeek's [JSON Output instructions](https://api-docs.deepseek.com/guides/json_mode/)
request JSON mode, a JSON prompt/example and an appropriate output allowance.
The existing request already supplies JSON mode and a syntax example. A guarantee
of syntactically valid JSON does not establish this application's stricter
duplicate/finite/Unicode or requirement semantics; the historical `result_json`
classification alone does not prove the provider violated JSON mode.
The [Responses API](https://api-docs.deepseek.com/api/create-response/) documents
another schema format, but this repair does not change endpoint or transport.
