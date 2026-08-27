package com.deepresearch.service;

import com.deepresearch.web.dto.AgentMemoryRequest;
import com.deepresearch.web.dto.AgentMemoryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/** 长期记忆 CRUD 与 last-used 标记的 PostgreSQL 访问层。 */
@Repository
class AgentMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    AgentMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    AgentMemoryResponse insert(String memoryId, String userId, AgentMemoryRequest request, double confidence) {
        jdbcTemplate.update("""
                INSERT INTO user_memory (memory_id, user_id, memory_type, content, source, confidence)
                VALUES (?, ?, ?, ?, ?, ?)
                """, memoryId, userId, request.memoryType().trim(), request.content().trim(),
                request.source() == null || request.source().isBlank() ? "manual" : request.source().trim(),
                confidence);
        return get(memoryId);
    }

    List<AgentMemoryResponse> list(String userId, int limit) {
        return jdbcTemplate.query("""
                SELECT memory_id, user_id, memory_type, content, source, confidence, created_at, updated_at
                FROM user_memory
                WHERE user_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
                """, this::map, userId, limit);
    }

    boolean delete(String memoryId, String userId) {
        return jdbcTemplate.update(
                "DELETE FROM user_memory WHERE memory_id = ? AND user_id = ?", memoryId, userId) > 0;
    }

    void markUsed(String memoryId) {
        jdbcTemplate.update("UPDATE user_memory SET last_used_at = now() WHERE memory_id = ?", memoryId);
    }

    private AgentMemoryResponse get(String memoryId) {
        return jdbcTemplate.queryForObject("""
                SELECT memory_id, user_id, memory_type, content, source, confidence, created_at, updated_at
                FROM user_memory WHERE memory_id = ?
                """, this::map, memoryId);
    }

    private AgentMemoryResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new AgentMemoryResponse(
                rs.getString("memory_id"), rs.getString("user_id"), rs.getString("memory_type"),
                rs.getString("content"), rs.getString("source"), rs.getDouble("confidence"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
