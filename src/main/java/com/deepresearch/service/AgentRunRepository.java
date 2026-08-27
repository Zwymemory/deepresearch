package com.deepresearch.service;

import com.deepresearch.web.dto.AgentResearchResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Agent run、step 与事件的 PostgreSQL 写入层；事务边界由 AgentStateService 统一控制。 */
@Repository
class AgentRunRepository {

    private final JdbcTemplate jdbcTemplate;

    AgentRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void insert(String question, AgentResearchResponse response, String userId) {
        jdbcTemplate.update("""
                INSERT INTO agent_run (run_id, session_id, user_id, question, answer, rounds, finished)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id) DO NOTHING
                """, response.runId(), response.sessionId(), userId, question, response.answer(),
                response.rounds(), response.finished());

        for (AgentResearchResponse.Step step : response.steps()) {
            jdbcTemplate.update("""
                    INSERT INTO agent_step (run_id, round, thought, action, action_input, observation)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (run_id, round) DO NOTHING
                    """, response.runId(), step.round(), null, step.action(), null,
                    step.decisionSummary() + " [" + step.outcomeCode() + "]");
        }
        for (AgentResearchResponse.Event event : response.events()) {
            jdbcTemplate.update("""
                    INSERT INTO agent_event (run_id, seq, type, message, round, action)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (run_id, seq) DO NOTHING
                    """, response.runId(), event.seq(), event.type(), event.message(), event.round(), event.action());
        }
    }

    boolean belongsToUser(String runId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_run WHERE run_id = ? AND user_id = ?",
                Integer.class, runId, userId);
        return count != null && count > 0;
    }
}
