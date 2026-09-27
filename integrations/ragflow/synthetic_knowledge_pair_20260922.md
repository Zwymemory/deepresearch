# DeepResearch migration evaluation fixture

This document contains invented migration rules for retrieval testing only. It is not product documentation. Each section has one answer marker so both search pipelines can be scored against the same text.

## Ingest authorization
DR-EVAL-01: Only an ADMIN may upload a knowledge-base document through the Java API.

## Upload checksum
DR-EVAL-02: An unchanged content SHA-256 hash reuses the existing document version instead of triggering another parse.

## Parse state
DR-EVAL-03: A remote document is ready for retrieval only after its parse state becomes DONE.

## Parse failure
DR-EVAL-04: A failed remote parse is recorded as FAILED and the error must remain visible for reconciliation.

## Dataset permission
DR-EVAL-05: The Java gateway sends only dataset IDs from its configured allowlist.

## Client secret
DR-EVAL-06: The RAGFlow API key stays on the server and is never copied into browser responses.

## Evidence marker
DR-EVAL-07: A displayed citation marker uses the format [来源N], where N starts at one for each response.

## Persistent source
DR-EVAL-08: A stable RAGFlow citation begins with kb:ragflow: and includes dataset, document, and chunk IDs.

## Similarity score
DR-EVAL-09: The normalized evidence score is the RAGFlow similarity value; it is not a legacy RRF score.

## Missing page
DR-EVAL-10: If a parser does not provide a page number, the evidence page number remains empty.

## Local context expansion
DR-EVAL-11: RAGFlow mode does not read neighbor chunks from the local vector_store table.

## Empty evidence
DR-EVAL-12: When no chunks are returned, the evidence list is empty and the answer status is INSUFFICIENT_EVIDENCE.

## Keyword rebuild
DR-EVAL-13: The reindex-keyword operation applies only to the legacy search path.

## Rollback selector
DR-EVAL-14: The retrieval.provider setting selects legacy for rollback and ragflow for the new data plane.

## Duplicate document
DR-EVAL-15: A document mapping links the legacy Java document ID to a separate remote RAGFlow document ID.

## Citation lookup
DR-EVAL-16: A RAGFlow citation is accepted only when its dataset, document, and chunk can be found through the chunk API.

## Query limit
DR-EVAL-17: The gateway clamps the requested topK to its configured maximum result count.

## Untrusted content
DR-EVAL-18: Retrieved document text is explicitly marked untrusted before the answer model sees it.

## Prompt marker injection
DR-EVAL-19: Citation-like strings inside source text are neutralized so they cannot impersonate answer citations.

## Error code P403
DR-EVAL-20: ERR-P403 means that a caller without ADMIN permission attempted a knowledge-base write.

## Error code R429
DR-EVAL-21: ERR-R429 means a retrieval budget was exceeded and the current request should stop.

## Error code C404
DR-EVAL-22: ERR-C404 means a cited chunk could not be found on the remote document.

## Batch number 73018
DR-EVAL-23: Batch 73018 is the fixed validation batch used for the synthetic migration document.

## Run number 59264
DR-EVAL-24: Run 59264 is reserved as the synthetic example for event replay checks.

## Threshold number 0.42
DR-EVAL-25: The synthetic retrieval threshold example is 0.42 and must not be copied into production defaults.
