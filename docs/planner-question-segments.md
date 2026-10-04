# Planner question segments

Fresh autonomous runs checkpoint `agent-planning-segments/2` before the first
model admission. Initialized checkpoints without a marker retain the original
span planner, including its schema, instruction, payload and request identity.
Unknown or explicitly null versions fail. The first pending/settled decision
does not need a frozen manifest to select the legacy protocol.

The server builds `agent-question-segments/1` from the exact question. It uses
Unicode codepoint ranges while enforcing the existing Java limit of 4000 UTF16
code units. Chinese clause punctuation, semicolons and line breaks delimit
reference units; ASCII periods, commas, question and exclamation marks delimit
when followed by whitespace or the end. Internal domain-name/decimal dots are
preserved. This is a lexical policy, not a full URL parser or semantic extractor.
Repeated text, whitespace, emoji, combining marks and CRLF remain exact.
Adjacent units are deterministically grouped when there would be more than 16;
no text is dropped. Long groups can contain multiple obligations and may need
to be referenced by several separate requirements.

The mapping includes exact unit text, start/end, full-question SHA256, IDs of the
form `qs1-<question SHA256>-<ordinal>`, and a canonical mapping SHA256. The v2
payload carries the original question once as ordered unit text. Models return:

```json
{
  "planner_contract": "agent-planning-segments/2",
  "action": "finish",
  "reason": "Brief public rationale",
  "requirements": [{
    "text": "An independently stated obligation",
    "segment_ids": ["qs1-<server supplied hash>-00"],
    "kind": "factual",
    "applicability": {
      "subject": "The subject to verify",
      "version": {"status": "unknown", "value": null, "reason": "Not established"},
      "valid_at": {"status": "unknown", "value": null, "reason": "Not established"},
      "conditions": []
    }
  }]
}
```

This is syntax only, not a claim or a valid publication. Previously the planner
had to return `question_spans: [{"start": ..., "end": ...}]`. New planners cannot
return coordinates, hashes or source maps. Unknown/foreign IDs, duplicates within
one requirement and unselected nonblank units reject. Shared qualifiers can be
referenced by several requirements; selected adjacent ranges merge into server
computed spans. Existing 32-requirement, 16-span, call, token and 1024-output-token
limits remain. Bounded mapping/input size does not guarantee that every possible
semantic decomposition fits that output allowance.

After schema/domain validation the server settles a canonical numeric-span
decision with an explicit v2 marker under `agent-planner-settlement/1`. Its
binding retains the bounded validated function declaration, its
`wire_response_sha256`, mapping/question/version binding and the canonical
value's `response_sha256`. CHECK response hashes retain their existing meaning.
Settled replay reconstructs the wire declaration, revalidates the original
schema/domain and recomputes the canonical result, requiring exact equality;
there is no extra model call. The envelope must fit the existing 120000-byte
ledger limit before settlement. An oversized envelope becomes a nonretryable
unusable UNKNOWN receipt with known usage counted once. Validated declarations
are not copied into usage metadata or failure diagnostics.

Canonical `agent-original-requirements/1` manifests, durable IDs and hashes are
unchanged. The SQL exact-settled-declaration trigger stays intact, with no schema
migration. Java publication and Python native audit consumers independently
verify v2 provenance and reconstruct the same canonical requirements. Historical
v1 declarations are interpreted as before and never rewritten. Safe omission
diagnostics expose only fixed mapping version/hash, missing count and at most
16 non-content ranges; untrusted error strings/IDs are not exported.

Selecting every unit proves mechanical coordinate coverage only. It does not
certify that the model understood all independent questions. Distinct criterion,
Claim scope, original read/quote/check and publication requirements still apply;
independent semantic review remains necessary. Controlled offline success is
not evidence of real web research acceptance. The rejected live output was not
retained, so neither its exact omission nor older failure causes are recovered.
