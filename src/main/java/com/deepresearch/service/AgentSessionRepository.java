package com.deepresearch.service;

import com.deepresearch.web.dto.AgentSessionSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/** Session、消息窗口与摘要状态的 PostgreSQL 访问层；不包含记忆筛选或压缩决策。 */
@Repository
class AgentSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    AgentSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void upsertOwned(String sessionId, String userId, String title) {
        List<String> owners = jdbcTemplate.query("""
                INSERT INTO agent_session (session_id, user_id, title)
                VALUES (?, ?, ?)
                ON CONFLICT (session_id) DO UPDATE SET updated_at = now()
                WHERE agent_session.user_id = EXCLUDED.user_id
                RETURNING user_id
                """, (rs, rowNum) -> rs.getString("user_id"), sessionId, userId, title);
        if (owners.isEmpty()) {
            throw new ResponseStatusException(FORBIDDEN, "sessionId 不属于当前用户");
        }
    }

    String summary(String sessionId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT summary FROM agent_session WHERE session_id = ?",
                (rs, rowNum) -> rs.getString("summary"), sessionId);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    int messageCount(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM agent_message WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    int summaryMessageCount(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT summary_message_count FROM agent_session WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    List<String> recentConversation(String sessionId, int limit) {
        List<String> rows = jdbcTemplate.query("""
                SELECT role, content
                FROM agent_message
                WHERE session_id = ?
                ORDER BY created_at DESC,
                         CASE WHEN role = 'assistant' THEN 0 ELSE 1 END ASC
                LIMIT ?
                """, (rs, rowNum) -> roleLabel(rs.getString("role")) + ": " + rs.getString("content"),
                sessionId, limit);
        Collections.reverse(rows);
        return rows;
    }

    List<String> messagesForSummary(String sessionId, int offset, int limit) {
        return jdbcTemplate.query("""
                SELECT role, content
                FROM agent_message
                WHERE session_id = ?
                ORDER BY created_at ASC,
                         CASE WHEN role = 'user' THEN 0 ELSE 1 END ASC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> roleLabel(rs.getString("role")) + ": " + rs.getString("content"),
                sessionId, limit, offset);
    }

    void updateSummary(String sessionId, String summary, int messageCount) {
        jdbcTemplate.update("""
                UPDATE agent_session
                SET summary = ?, summary_message_count = ?, summary_updated_at = now(), updated_at = now()
                WHERE session_id = ? AND summary_message_count < ?
                """, summary, messageCount, sessionId, messageCount);
    }

    void insertMessage(String messageId, String sessionId, String runId, String role, String content) {
        jdbcTemplate.update("""
                INSERT INTO agent_message (message_id, session_id, run_id, role, content)
                VALUES (?, ?, ?, ?, ?)
                """, messageId, sessionId, runId, role, content);
    }

    void touch(String sessionId) {
        jdbcTemplate.update("UPDATE agent_session SET updated_at = now() WHERE session_id = ?", sessionId);
    }

    List<AgentSessionSummary> list(String userId, int limit) {
        return jdbcTemplate.query("""
                SELECT s.session_id, s.user_id, s.title, count(m.message_id)::int AS message_count,
                       s.created_at, s.updated_at
                FROM agent_session s
                LEFT JOIN agent_message m ON m.session_id = s.session_id
                WHERE s.user_id = ?
                GROUP BY s.session_id, s.user_id, s.title, s.created_at, s.updated_at
                ORDER BY s.updated_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new AgentSessionSummary(
                rs.getString("session_id"), rs.getString("user_id"), rs.getString("title"),
                rs.getInt("message_count"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class)), userId, limit);
    }

    private String roleLabel(String role) {
        return "assistant".equalsIgnoreCase(role) ? "助手" : "用户";
    }
}
