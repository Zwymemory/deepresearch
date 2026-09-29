package com.deepresearch.evidence;

import com.deepresearch.service.RagflowClient;
import com.deepresearch.service.RagflowDocumentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedSourceReaderTest {
    private static final EvidenceAuthority.Candidate CANDIDATE = new EvidenceAuthority.Candidate(
            "source", "knowledge", null, "dataset", "document", "chunk", "Synthetic original", "search");
    private static final String ORIGINAL = "Document version: 2.0\n\nSynthetic 🧪 limit is 20; only in general mode.\n";

    @Test void currentOriginalChunkWireShapeSurvivesRealHttpWithoutTextRewriting() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var path = new AtomicReference<String>();
        server.createContext("/api/v1/datasets/dataset/documents/document/chunks/chunk", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            byte[] response = canonical(object("code", 0, "data", object("id", "chunk", "doc_id", "document",
                    "kb_id", "dataset", "content_with_weight", ORIGINAL))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new RagflowClient(JSON, "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-key",
                    List.of("dataset"), Duration.ofSeconds(1), Duration.ofSeconds(2), 32, 128, 20, false, "builtin");
            var registry = mock(RagflowDocumentRegistry.class);
            when(registry.active("dataset", "document")).thenReturn(true);
            var document = reader(client, registry).read(null, CANDIDATE);
            assertThat(path.get()).isEqualTo("/api/v1/datasets/dataset/documents/document/chunks/chunk");
            assertThat(document.text()).isEqualTo(ORIGINAL);
            assertThat(document.rawResponseHash()).isEqualTo(sha(ORIGINAL));
            assertThat(document.locator()).isEqualTo(object("kind", "knowledge_chunk", "dataset_id", "dataset",
                    "document_id", "document", "chunk_id", "chunk"));
            assertThat(document.snapshotKind()).isEqualTo("document_chunk");
            assertThat(document.truncated()).isFalse();
            assertThat(document.verifiedObservation()).isFalse();
            verify(registry, times(2)).active("dataset", "document");
        } finally {
            server.stop(0);
        }
    }

    @Test void legacyContentAndEqualAliasesKeepTheSameOriginal() {
        var fixture = new Fixture();
        for (JsonNode chunk : List.of(object("id", "chunk", "doc_id", "document", "content", ORIGINAL),
                object("id", "chunk", "doc_id", "document", "kb_id", "dataset", "content", ORIGINAL,
                        "content_with_weight", ORIGINAL))) {
            fixture.chunk(chunk);
            var document = fixture.reader.read(null, CANDIDATE);
            assertThat(document.text()).isEqualTo(ORIGINAL);
            assertThat(document.rawResponseHash()).isEqualTo(sha(ORIGINAL));
        }
    }

    @Test void conflictingAliasesAndMissingOrNonTextOriginalsAreRejected() {
        var fixture = new Fixture();
        fixture.chunk(object("id", "chunk", "doc_id", "document", "content", "Search preview",
                "content_with_weight", ORIGINAL));
        assertThatThrownBy(() -> fixture.reader.read(null, CANDIDATE)).hasMessageContaining("SOURCE_KB_ORIGINAL_AMBIGUOUS");
        for (JsonNode chunk : List.of(object("id", "chunk", "doc_id", "document"),
                object("id", "chunk", "doc_id", "document", "content_with_weight", 20),
                object("id", "chunk", "doc_id", "document", "content_with_weight", "   "))) {
            fixture.chunk(chunk);
            assertThatThrownBy(() -> fixture.reader.read(null, CANDIDATE)).hasMessageContaining("EVIDENCE_TEXT_INVALID");
        }
    }

    @Test void changedChunkDocumentOrReturnedDatasetIdentityIsRejected() {
        var fixture = new Fixture();
        for (JsonNode chunk : List.of(object("id", "other", "doc_id", "document", "content_with_weight", ORIGINAL),
                object("id", "chunk", "doc_id", "other", "content_with_weight", ORIGINAL),
                object("id", "chunk", "doc_id", "document", "kb_id", "other", "content_with_weight", ORIGINAL),
                object("id", "chunk", "doc_id", "document", "dataset_id", "other", "content_with_weight", ORIGINAL))) {
            fixture.chunk(chunk);
            assertThatThrownBy(() -> fixture.reader.read(null, CANDIDATE)).hasMessageContaining("SOURCE_KB_IDENTITY_CHANGED");
        }
    }

    @Test void allowlistAndActiveRegistryAreRequiredAndRegistryIsRecheckedAfterFetch() {
        var fixture = new Fixture();
        when(fixture.registry.active("dataset", "document")).thenReturn(false);
        assertThatThrownBy(() -> fixture.reader.read(null, CANDIDATE)).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        verify(fixture.client, never()).chunk(anyString(), anyString(), anyString());
        when(fixture.client.datasets()).thenReturn(List.of("different-dataset"));
        assertThatThrownBy(() -> fixture.reader.read(null, CANDIDATE)).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        verify(fixture.client, never()).chunk(anyString(), anyString(), anyString());
        when(fixture.client.datasets()).thenReturn(List.of("dataset"));
        when(fixture.registry.active("dataset", "document")).thenReturn(true, false);
        fixture.chunk(object("id", "chunk", "doc_id", "document", "kb_id", "dataset", "content_with_weight", ORIGINAL));
        assertThatThrownBy(() -> fixture.reader.read(null, CANDIDATE)).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        verify(fixture.client).chunk("dataset", "document", "chunk");
    }

    private static ManagedSourceReader reader(RagflowClient client, RagflowDocumentRegistry registry) {
        return new ManagedSourceReader(new SafeWebReader(), client, registry, EvidenceAuthority.denyAll());
    }

    private static final class Fixture {
        final RagflowClient client = mock(RagflowClient.class);
        final RagflowDocumentRegistry registry = mock(RagflowDocumentRegistry.class);
        final ManagedSourceReader reader = reader(client, registry);
        Fixture() {
            when(client.datasets()).thenReturn(List.of("dataset"));
            when(registry.active("dataset", "document")).thenReturn(true);
        }
        void chunk(JsonNode value) {
            when(client.chunk("dataset", "document", "chunk")).thenReturn(value);
        }
    }
}
