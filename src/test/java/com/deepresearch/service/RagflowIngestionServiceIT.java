package com.deepresearch.service;

import com.deepresearch.model.IngestStatus;
import com.deepresearch.model.ParsedDocument;
import com.deepresearch.model.ParsedSection;
import com.deepresearch.model.SourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** SQL-backed ingestion tests run by Failsafe's integration profile. */
@Testcontainers
class RagflowIngestionServiceIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch")
            .withUsername("deepresearch")
            .withPassword("deepresearch");
    private static JdbcTemplate db;
    private RagflowClient client;
    private RagflowIngestionService service;

    @BeforeAll
    static void database() throws Exception {
        var source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = source.getConnection()) {
            for (String migration : List.of("V1__kb_ingestion.sql", "V13__ragflow_document_mapping.sql",
                    "V14__ragflow_sync_recovery.sql"))
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
        }
        db = new JdbcTemplate(source);
    }

    @BeforeEach
    void setup() throws Exception {
        db.execute("TRUNCATE kb_document CASCADE");
        client = mock(RagflowClient.class);
        when(client.datasets()).thenReturn(List.of("ds"));
        when(client.findDocumentsByName(eq("ds"), anyString())).thenReturn(List.of());
        when(client.document(eq("ds"), anyString())).thenReturn(JSON.readTree("{\"run\":\"UNSTART\"}"));
        service = new RagflowIngestionService(db, client);
    }

    @Test
    void rollsBackNewDocumentWhenDurableJobInsertFails() {
        // Fault injection: a null dataset lets the document insert succeed, then violates the job constraint.
        when(client.datasets()).thenReturn(Collections.singletonList(null));

        assertThatThrownBy(() -> service.ingest(document("new text"), false))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(db.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM kb_ragflow_sync_job", Integer.class)).isZero();
    }

    @Test
    void parsingAttemptReturnsPersistedVersion() throws Exception {
        insertLegacy("same text");
        insertJob("PARSING", 7, "remote-existing", null);
        when(client.document("ds", "remote-existing")).thenReturn(JSON.readTree("{\"run\":\"RUNNING\"}"));

        var result = service.ingestExisting("doc-old", document("same text"));

        assertThat(result.status()).isEqualTo(IngestStatus.PARSING);
        assertThat(result.version()).isEqualTo(7);
    }

    @Test
    void failedAttemptRetryReturnsPersistedVersion() throws Exception {
        insertLegacy("same text");
        insertJob("FAILED", 7, "remote-existing", "previous failure");
        when(client.document("ds", "remote-existing")).thenReturn(JSON.readTree("{\"run\":\"RUNNING\"}"));

        var result = service.ingestExisting("doc-old", document("same text"));

        assertThat(result.status()).isEqualTo(IngestStatus.PARSING);
        assertThat(result.version()).isEqualTo(7);
        verify(client, never()).upload(anyString(), anyString(), any(byte[].class));
    }

    @Test
    void prewarmsLegacyDocumentBeforeSwitchingProviderAndPromotesOnlyAfterDone() throws Exception {
        insertLegacy("old text");
        when(client.upload(eq("ds"), anyString(), any(byte[].class))).thenReturn("remote-new");
        ParsedDocument update = document("old text");
        assertThat(service.ingestExisting("doc-old", update).status()).isEqualTo(IngestStatus.PARSING);
        assertThat(db.queryForObject("SELECT status FROM kb_document WHERE doc_id='doc-old'", String.class)).isEqualTo("DONE");
        assertThat(db.queryForObject("SELECT count(*) FROM kb_ragflow_document", Integer.class)).isZero();
        assertThat(service.status("doc-old").get("status")).isEqualTo("PARSING");

        when(client.document("ds", "remote-new")).thenReturn(JSON.readTree("{\"run\":\"DONE\",\"chunk_count\":2}"));
        service.reconcile("doc-old");
        assertThat(db.queryForObject("SELECT document_id FROM kb_ragflow_document WHERE legacy_doc_id='doc-old'", String.class))
                .isEqualTo("remote-new");
        assertThat(db.queryForObject("SELECT status FROM kb_document WHERE doc_id='doc-old'", String.class)).isEqualTo("DONE");
        assertThat(db.queryForObject("SELECT count(*) FROM kb_ragflow_sync_job", Integer.class)).isZero();
    }

    @Test
    void recoversUploadThatSucceededBeforeItsRemoteIdWasSaved() throws Exception {
        insertLegacy("old text");
        String hash = StructuralChunker.sha256("old text");
        db.update("""
                INSERT INTO kb_ragflow_sync_job(legacy_doc_id,dataset_id,remote_name,content_hash,version,
                    title,source_type,filename,raw_content,status)
                VALUES('doc-old','ds','dr-doc-old-v2-saved.txt',?,2,'Guide','TEXT','Guide.txt','old text','PREPARED')
                """, hash);
        when(client.findDocumentsByName("ds", "dr-doc-old-v2-saved.txt")).thenReturn(List.of("remote-orphan"));

        service.reconcile("doc-old");
        verify(client, never()).upload(anyString(), anyString(), any(byte[].class));
        verify(client).parse("ds", "remote-orphan");
        assertThat(db.queryForObject("SELECT remote_document_id FROM kb_ragflow_sync_job", String.class))
                .isEqualTo("remote-orphan");
    }

    @Test
    void acceptedParseIsNotSubmittedAgainAfterProcessRestart() throws Exception {
        insertLegacy("old text");
        db.update("""
                INSERT INTO kb_ragflow_sync_job(legacy_doc_id,dataset_id,remote_name,remote_document_id,
                    content_hash,version,title,source_type,filename,raw_content,status)
                VALUES('doc-old','ds','dr-doc-old-v2-parse.txt','remote-parsing',?,2,
                    'Guide','TEXT','Guide.txt','old text','UPLOADED')
                """, StructuralChunker.sha256("old text"));
        when(client.document("ds", "remote-parsing")).thenReturn(JSON.readTree("{\"run\":\"RUNNING\"}"));

        service.reconcile("doc-old");
        verify(client, never()).parse(anyString(), anyString());
        assertThat(service.status("doc-old").get("status")).isEqualTo("PARSING");
    }

    @Test
    void deletingDocumentRemovesActiveAndPreviousRemoteVersions() {
        insertLegacy("old text");
        db.update("""
                INSERT INTO kb_ragflow_document(legacy_doc_id,dataset_id,document_id,previous_document_id,
                    version,content_hash,sync_status)
                VALUES('doc-old','ds','remote-active','remote-previous',2,?,'DONE')
                """, StructuralChunker.sha256("old text"));

        service.delete("doc-old");
        verify(client).delete("ds", "remote-active");
        verify(client).delete("ds", "remote-previous");
        assertThat(db.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isZero();
    }

    @Test
    void failedReplacementKeepsOldCitationAndRetryReusesSameRemoteDocument() throws Exception {
        insertLegacy("old text");
        String oldHash = StructuralChunker.sha256("old text");
        db.update("""
                INSERT INTO kb_ragflow_document(legacy_doc_id,dataset_id,document_id,version,content_hash,sync_status)
                VALUES('doc-old','ds','remote-old',1,?,'DONE')
                """, oldHash);
        when(client.upload(eq("ds"), anyString(), any(byte[].class))).thenReturn("remote-new");
        when(client.document("ds", "remote-new")).thenReturn(
                JSON.readTree("{\"run\":\"UNSTART\"}"),
                JSON.readTree("{\"run\":\"FAIL\"}"),
                JSON.readTree("{\"run\":\"FAIL\"}"),
                JSON.readTree("{\"run\":\"DONE\",\"chunk_count\":1}"));

        service.ingestExisting("doc-old", document("new text"));
        service.reconcile("doc-old");
        assertThat(service.status("doc-old").get("status")).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT document_id FROM kb_ragflow_document", String.class)).isEqualTo("remote-old");
        service.ingestExisting("doc-old", document("new text"));
        service.reconcile("doc-old");
        verify(client, times(1)).upload(eq("ds"), anyString(), any(byte[].class));
        assertThat(db.queryForObject("SELECT document_id FROM kb_ragflow_document", String.class)).isEqualTo("remote-new");
        verify(client).delete("ds", "remote-old");
    }

    @Test
    void simultaneousCallsForOneDocumentShareTheDurableAttempt() throws Exception {
        insertLegacy("old text");
        CountDownLatch enteredUpload = new CountDownLatch(1);
        CountDownLatch releaseUpload = new CountDownLatch(1);
        when(client.upload(eq("ds"), anyString(), any(byte[].class))).thenAnswer(invocation -> {
            enteredUpload.countDown();
            if (!releaseUpload.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test upload timeout");
            return "remote-new";
        });
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> service.ingestExisting("doc-old", document("old text")));
            assertThat(enteredUpload.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.ingestExisting("doc-old", document("old text")).status()).isEqualTo(IngestStatus.PARSING);
            releaseUpload.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(IngestStatus.PARSING);
        } finally { releaseUpload.countDown(); threads.shutdownNow(); }
        verify(client, times(1)).upload(eq("ds"), anyString(), any(byte[].class));
    }

    private static void insertLegacy(String raw) {
        db.update("""
                INSERT INTO kb_document(doc_id,title,source_type,filename,raw_content,content_hash,version,chunk_count,status)
                VALUES('doc-old','Guide','TEXT','Guide.txt',?,?,1,1,'DONE')
                """, raw, StructuralChunker.sha256(raw));
    }

    private static void insertJob(String status, int version, String remoteId, String error) {
        db.update("""
                INSERT INTO kb_ragflow_sync_job(legacy_doc_id,dataset_id,remote_name,remote_document_id,
                    content_hash,version,title,source_type,filename,raw_content,status,error_message)
                VALUES('doc-old','ds',?, ?, ?, ?, 'Guide','TEXT','Guide.txt','same text',?,?)
                """, "dr-doc-old-v" + version + "-persisted.txt", remoteId,
                StructuralChunker.sha256("same text"), version, status, error);
    }

    private static ParsedDocument document(String raw) {
        return new ParsedDocument("Guide", SourceType.TEXT, "Guide.txt", raw,
                List.of(new ParsedSection("正文", null, raw)));
    }
}
