# RAGFlow local smoke run — 2026-09-22

This run exercised the opt-in `ragflow` Java route against the local RAGFlow
v0.27.2 service. It is a synthetic parser and retrieval smoke test, not the
paired project-knowledge acceptance gate. The committed default remains
`legacy`.

## Conditions

| Item | Value |
| --- | --- |
| Java revision | `09ca042` plus the evaluation note in this commit |
| RAGFlow API | `http://127.0.0.1:9380` |
| Java API | `http://127.0.0.1:8080`, host JAR |
| Data stores | Isolated PostgreSQL 16/pgvector on `15432`, Elasticsearch 8.15.3 on `19201` |
| Dataset | Dedicated `DeepResearch-RAGFlow-Migration-Eval-20260922`, ID `4b43ea48b67111f1811e952527b08ce2` |
| Corpus | One active copy of `synthetic_knowledge.md`; SHA-256 `3f142361f5f03c6bd1101643cb45d544dca090eda10632706c6383a368288202` |
| Final document | Java ID `doc-aaeae337-6b8d-4689-8aea-eac8f60de9d8`; RAGFlow ID `4d3056f0b67311f1811e952527b08ce2`; 7 parsed chunks |
| Queries | `synthetic_cases.json`: 25 positive, 3 no-evidence; `topK=5`; one measured call per query, sequential |
| Final configuration | Naive parser `chunk_token_num=128`; `DEEPRESEARCH_RAGFLOW_SIMILARITY_THRESHOLD=0.22` on the test process |

The API key was held in an ignored local `.env`; it is absent from this note.
The local Java process used a placeholder Zhipu key only to satisfy startup
validation; the RAGFlow path did not call Java's legacy embedding model.

## Results

| Naive chunk tokens | Similarity threshold | Positive anchors visible in returned context | Negative queries with no evidence |
| ---: | ---: | ---: | ---: |
| 512 | 0.20 | 10/25 | 2/3 |
| 128 | 0.20 | 25/25 | 2/3 |
| 128 | 0.22 | 25/25 | 3/3 |

The [final per-case capture](live_smoke_2026-09-22.json) records hits,
evidence counts, maximum similarity and one-call latency without credentials.

Each row used one active copy of the same synthetic document. With 512-token
chunks, RAGFlow produced two chunks. Both contained all 25 gold anchors in
their full text, but the Java evidence limit of 700 characters per chunk hid
15 anchors from the answer context. At 128 tokens, RAGFlow produced seven
chunks, each shorter than 700 characters. The threshold change removed the
remaining false-positive retrieval for the synthetic payroll question; it was
selected after inspecting this test set and is not an independently validated
production threshold.

On the final configuration, all seven chunks passed direct API lookup with
matching dataset, document and chunk IDs. A real `/api/research/hybrid`
answer included `[来源1]` and a corresponding persistent
`kb:ragflow:<dataset>:<document>:<chunk>` source. Java upload returned
`PARSING`, the mapping poll reached `DONE`, repeat upload returned `UNCHANGED`,
and deletion removed the earlier test document from subsequent retrieval.
For the synthetic payroll negative, the answer API returned its no-evidence
message and an empty source list.
After a Java process restart, the persisted mapping still yielded the same
RAGFlow document ID through the debug endpoint.
The final 28 sequential debug calls had an observed p95 of 1,248.1 ms, with
no warmup or repetitions; this is not the performance gate measurement.

The live run exposed a compatibility issue: this RAGFlow version returns
business code 102 when a `name=` document filter finds no document. The
client now pages through documents and matches the name locally, which made
first-time Java upload work. The regression test simulates the code 102
response.

## Remaining acceptance work

At the time of this earlier smoke run, the project-knowledge corpus had not
yet been synchronized into both routes and the Java legacy route lacked a
usable Zhipu embedding key. The subsequent
[project paired evaluation](PROJECT_PAIRED_RESULT_2026-09-22.md) and
[synthetic paired evaluation](SYNTHETIC_PAIRED_RESULT_2026-09-22.md) supersede
that measurement gap. No cutover decision follows from this smoke run.
The project negative prompts also need a reviewed retrieval definition:
their existing answer-level Gold permits evidence-backed safe denial while
the retrieval-only gate demands an empty result. Broad positive anchors
must be replaced with distinctive, verified snippets before paired scoring.
