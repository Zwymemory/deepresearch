package com.deepresearch.service;

import com.deepresearch.web.dto.AgentReportResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AgentReportService {

    private final JdbcTemplate jdbcTemplate;

    public AgentReportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AgentReportResponse report(int recentLimit) {
        long feedbacks = count("agent_feedback");
        long badCases = jdbcTemplate.queryForObject("SELECT count(*) FROM agent_feedback WHERE rating = 'DOWN'", Long.class);
        return new AgentReportResponse(
                OffsetDateTime.now(),
                count("agent_session"),
                count("agent_run"),
                count("agent_message"),
                count("user_memory"),
                feedbacks,
                badCases,
                feedbacks == 0 ? 0.0 : Math.round((badCases * 1.0 / feedbacks) * 10000.0) / 10000.0,
                toolUsage(),
                recentRuns(Math.max(1, Math.min(recentLimit, 50)))
        );
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private List<AgentReportResponse.ToolUsage> toolUsage() {
        return jdbcTemplate.query("""
                SELECT action, count(*) AS n
                FROM agent_step
                WHERE action IS NOT NULL AND action <> '' AND action <> 'final'
                GROUP BY action
                ORDER BY n DESC, action ASC
                """, (rs, rowNum) -> new AgentReportResponse.ToolUsage(
                rs.getString("action"),
                rs.getLong("n")
        ));
    }

    private List<AgentReportResponse.RecentRun> recentRuns(int limit) {
        return jdbcTemplate.query("""
                SELECT run_id, session_id, user_id, question, finished, rounds, created_at
                FROM agent_run
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new AgentReportResponse.RecentRun(
                rs.getString("run_id"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("question"),
                rs.getBoolean("finished"),
                rs.getInt("rounds"),
                rs.getObject("created_at", OffsetDateTime.class)
        ), limit);
    }
}
