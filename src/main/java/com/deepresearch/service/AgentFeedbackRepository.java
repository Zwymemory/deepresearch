package com.deepresearch.service;

import com.deepresearch.web.dto.AgentBadCaseResponse;
import com.deepresearch.web.dto.AgentFeedbackRequest;
import com.deepresearch.web.dto.AgentFeedbackResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/** Feedback 写入与 bad-case 查询的 PostgreSQL 访问层。 */
@Repository
class AgentFeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    AgentFeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    AgentFeedbackResponse insert(String feedbackId, String runId, String rating, AgentFeedbackRequest request) {
        jdbcTemplate.update("""
                INSERT INTO agent_feedback (feedback_id, run_id, rating, reason, comment)
                VALUES (?, ?, ?, ?, ?)
                """, feedbackId, runId, rating,
                AgentIdentitySupport.blankToNull(request.reason()),
                AgentIdentitySupport.blankToNull(request.comment()));
        return jdbcTemplate.queryForObject("""
                SELECT feedback_id, run_id, rating, reason, comment, created_at
                FROM agent_feedback WHERE feedback_id = ?
                """, (rs, rowNum) -> new AgentFeedbackResponse(
                rs.getString("feedback_id"), rs.getString("run_id"), rs.getString("rating"),
                rs.getString("reason"), rs.getString("comment"),
                rs.getObject("created_at", OffsetDateTime.class)), feedbackId);
    }

    List<AgentBadCaseResponse> listBadCases(int limit) {
        return jdbcTemplate.query("""
                SELECT f.feedback_id, f.run_id, r.session_id, r.user_id, r.question, r.answer,
                       f.rating, f.reason, f.comment, f.created_at
                FROM agent_feedback f
                JOIN agent_run r ON r.run_id = f.run_id
                WHERE f.rating = 'DOWN'
                ORDER BY f.created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new AgentBadCaseResponse(
                rs.getString("feedback_id"), rs.getString("run_id"), rs.getString("session_id"),
                rs.getString("user_id"), rs.getString("question"), rs.getString("answer"),
                rs.getString("rating"), rs.getString("reason"), rs.getString("comment"),
                rs.getObject("created_at", OffsetDateTime.class)), limit);
    }
}
