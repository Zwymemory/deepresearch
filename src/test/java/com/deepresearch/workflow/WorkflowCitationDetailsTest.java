package com.deepresearch.workflow;

import com.deepresearch.agent.CitationDetail;
import com.deepresearch.mcp.McpKnowledgeTools.Evidence;
import com.deepresearch.mcp.McpKnowledgeTools.McpToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkflowCitationDetailsTest {
    final ObjectMapper mapper = new ObjectMapper();
    final WorkflowRepository repository = mock(WorkflowRepository.class);
    final WorkflowRepository.RunRow run = new WorkflowRepository.RunRow(
            "run-current", "session", "tenant:owner", "question", "{}", "/api/research/workflows",
            "key", "hash", "thread", "FINALIZING", "FINALIZING", null, false, List.of("kb_search", "web_search"),
            "grant", null, null, null, "{}", "{}", null, null, null, null, 1, null, null);
    final CitationDetail web = CitationDetail.web("https://example.org/a", "Web title", "https://example.org/a", "Web excerpt");
    final CitationDetail kb = CitationDetail.knowledge("doc:chunk", "Knowledge title", "Knowledge excerpt");

    WorkflowRepository.ToolReceiptRow receipt(String tool, CitationDetail... details) throws Exception {
        var evidence = java.util.Arrays.stream(details).map(detail -> new Evidence(
                detail.sourceId(), tool, "untrusted parsed title", "", "parsed excerpt", "digest")).toList();
        return new WorkflowRepository.ToolReceiptRow("call", "task", tool, "hash",
                mapper.writeValueAsString(new McpToolResponse(true, "OK", tool, evidence, List.of(details))));
    }

    List<CitationDetail> capture(List<String> citations, WorkflowRepository.ToolReceiptRow... receipts) {
        when(repository.completedSourceReceipts("run-current", "tenant:owner")).thenReturn(List.of(receipts));
        return WorkflowCitationDetails.capture(repository, mapper, run, citations);
    }

    @Test void projectsOnlyCitedTypedSnapshotsInCitationOrderAndDeduplicatesIdenticalReceipts() throws Exception {
        var details = capture(List.of(kb.sourceId(), web.sourceId()), receipt("web_search", web),
                receipt("kb_search", kb), receipt("web_search", web));
        assertThat(details).containsExactly(kb, web);
        assertThat(mapper.readTree(mapper.writeValueAsString(details)).get(0).get("url").isNull()).isTrue();
        assertThat(mapper.writeValueAsString(details)).doesNotContain("untrusted parsed title", "digest", "hash");
        verify(repository).completedSourceReceipts("run-current", "tenant:owner");
        verify(repository, never()).completedToolReceipts(anyString());
    }

    @Test void conflictingVariantsDoNotChooseOneVersion() throws Exception {
        var other = CitationDetail.web(web.sourceId(), "Changed title", web.url(), "Changed excerpt");
        var details = capture(List.of(web.sourceId()), receipt("web_search", web),
                receipt("web_search", other), receipt("web_search", web));
        assertThat(details).containsExactly(CitationDetail.unavailable(web.sourceId(), "AMBIGUOUS_SNAPSHOT"));
    }

    @Test void oldOrMalformedReceiptsAndMissingSourcesAreExplicitlyUnavailable() throws Exception {
        String legacy = mapper.writeValueAsString(new McpToolResponse(true, "OK", "web_search", List.of(
                new Evidence(web.sourceId(), "web_search", "Model-controlled title", web.url(), "guess", "digest"))));
        var old = new WorkflowRepository.ToolReceiptRow("old", "task", "web_search", "hash", legacy);
        var malformed = new WorkflowRepository.ToolReceiptRow("bad", "task", "web_search", "hash", "{bad");
        assertThat(capture(List.of(web.sourceId(), "missing"), old, malformed))
                .allSatisfy(detail -> {
                    assertThat(detail.metadataStatus()).isEqualTo("UNAVAILABLE");
                    assertThat(detail.unavailableReason()).isEqualTo("MISSING_SNAPSHOT");
                    assertThat(detail.title()).isNull();
                    assertThat(detail.url()).isNull();
                });
    }

    @Test void rejectsWrongToolOrIdentityBindingAndUnsafeOrMismatchedWebUrls() throws Exception {
        var response = new McpToolResponse(true, "OK", "web_search", List.of(), List.of(web));
        var wrong = new WorkflowRepository.ToolReceiptRow("wrong", "task", "kb_search", "hash", mapper.writeValueAsString(response));
        var javascript = CitationDetail.web(web.sourceId(), "Title", "javascript:alert(1)", "text");
        var credentials = CitationDetail.web(web.sourceId(), "Title", "https://user:pass@example.test/a", "text");
        var token = CitationDetail.web(web.sourceId(), "Title", "https://example.org/a?token=secret", "text");
        var mismatch = CitationDetail.web(web.sourceId(), "Title", "https://different.example.org/a", "text");
        assertThat(capture(List.of(web.sourceId()), wrong, receipt("web_search", javascript),
                receipt("web_search", credentials), receipt("web_search", token), receipt("web_search", mismatch)))
                .containsExactly(CitationDetail.unavailable(web.sourceId(), "MISSING_SNAPSHOT"));
    }

    @Test void resultBoundsFailClosedInsteadOfSilentlyResolvingAnIncompleteSet() throws Exception {
        when(repository.completedSourceReceipts("run-current", "tenant:owner"))
                .thenReturn(Collections.nCopies(257, receipt("web_search", web)));
        assertThat(WorkflowCitationDetails.capture(repository, mapper, run, List.of(web.sourceId())))
                .containsExactly(CitationDetail.unavailable(web.sourceId(), "SNAPSHOT_LIMIT"));
    }
}
