package com.deepresearch.service;

import com.deepresearch.model.IngestStatus;
import com.deepresearch.model.ParsedDocument;
import com.deepresearch.model.ParsedSection;
import com.deepresearch.model.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RagflowIngestionServiceTest {
    private JdbcTemplate db;
    private RagflowClient client;
    private Connection connection;
    private RagflowIngestionService service;

    @BeforeEach
    void setup() throws Exception {
        db = mock(JdbcTemplate.class);
        client = mock(RagflowClient.class);
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(db.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(client.datasets()).thenReturn(List.of("ds"));
        service = new RagflowIngestionService(db, client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollsBackDocumentInsertWhenDurableJobInsertFails() throws Exception {
        when(db.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(db.update(argThat(sql -> sql != null && sql.contains("INSERT INTO kb_document")), any(Object[].class)))
                .thenReturn(1);
        when(db.update(argThat(sql -> sql != null && sql.contains("INSERT INTO kb_ragflow_sync_job")), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("job insert failed"));

        assertThatThrownBy(() -> service.ingest(document("new text"), false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("job insert failed");

        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(db).update(argThat(sql -> sql != null && sql.contains("INSERT INTO kb_document")), any(Object[].class));
        verify(db).update(argThat(sql -> sql != null && sql.contains("INSERT INTO kb_ragflow_sync_job")), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsVersionFromExistingDurableJob() throws Exception {
        String hash = StructuralChunker.sha256("same text");
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> mapper = invocation.getArgument(1);
            if (sql.contains("FROM kb_document d")) return List.of(mapper.mapRow(documentRow(), 0));
            if (sql.contains("FROM kb_ragflow_sync_job")) return List.of(mapper.mapRow(jobRow(hash), 0));
            return List.of();
        }).when(db).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(db.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("INSERT INTO kb_ragflow_sync_job")) return 0;
            if (sql.contains("SET lock_token=")) return 0;
            return 1;
        });

        var result = service.ingestExisting("doc-old", document("same text"));

        assertThat(result.status()).isEqualTo(IngestStatus.PARSING);
        assertThat(result.version()).isEqualTo(3);
        verify(connection).commit();
    }

    private ResultSet documentRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("doc-old");
        when(row.getInt(2)).thenReturn(10);
        when(row.getString(5)).thenReturn("ds");
        return row;
    }

    private ResultSet jobRow(String hash) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("doc-old");
        when(row.getString(2)).thenReturn("ds");
        when(row.getString(3)).thenReturn("dr-doc-old-v3-persisted.txt");
        when(row.getString(5)).thenReturn(hash);
        when(row.getInt(6)).thenReturn(3);
        when(row.getString(7)).thenReturn("Guide");
        when(row.getString(8)).thenReturn("TEXT");
        when(row.getString(9)).thenReturn("Guide.txt");
        when(row.getString(10)).thenReturn("same text");
        when(row.getString(13)).thenReturn("PARSING");
        return row;
    }

    private static ParsedDocument document(String raw) {
        return new ParsedDocument("Guide", SourceType.TEXT, "Guide.txt", raw,
                List.of(new ParsedSection("正文", null, raw)));
    }
}
