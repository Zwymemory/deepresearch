package com.deepresearch.service;

import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Agent POST 的持久化幂等门闩。
 *
 * 唯一作用域是 tenant:user + endpoint + Idempotency-Key；同键异体拒绝，已完成结果重放，
 * 并发中的相同请求返回稳定冲突。若执行抛出异常，记录为 AMBIGUOUS，避免把“客户端没拿到结果”
 * 错当成“业务一定没执行”而盲目重试。
 */
@Service
public class AgentIdempotencyService {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";
    private static final String AMBIGUOUS = "AMBIGUOUS";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public AgentIdempotencyService(JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper,
                                   @Value("${deepresearch.idempotency.ttl:24h}") Duration ttl) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofHours(24) : ttl;
    }

    public Outcome execute(String storageUserId,
                           String endpoint,
                           String idempotencyKey,
                           AgentResearchRequest request,
                           Supplier<AgentResearchResponse> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new Outcome(action.get(), false);
        }
        return executePrepared(prepare(storageUserId, endpoint, idempotencyKey, request), action);
    }

    /**
     * 在返回 HTTP/SSE 200 之前同步完成 key 校验、唯一 claim 或已完成响应判定。
     * 这样非法 key、同键异体和进行中冲突都能成为真实的 4xx，而不是先建立 SSE 后突然断流。
     */
    public Prepared prepare(String storageUserId,
                            String endpoint,
                            String idempotencyKey,
                            AgentResearchRequest request) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        validateKey(key);
        String fingerprint = fingerprint(request);
        UUID claimId = claim(storageUserId, endpoint, key, fingerprint);
        if (claimId != null) {
            return new Prepared(storageUserId, endpoint, key, fingerprint, claimId, null);
        }
        return prepareExisting(storageUserId, endpoint, key, fingerprint);
    }

    public Outcome executePrepared(Prepared prepared, Supplier<AgentResearchResponse> action) {
        if (prepared.replayResponse() != null) {
            return new Outcome(prepared.replayResponse(), true);
        }
        return executeClaimed(
                prepared.storageUserId(), prepared.endpoint(), prepared.idempotencyKey(),
                prepared.requestFingerprint(), prepared.claimId(), action);
    }

    public StatusView status(String storageUserId, String endpoint, String idempotencyKey) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        validateKey(key);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT request_fingerprint, status, response_json, created_at, updated_at, expires_at
                FROM agent_idempotency_record
                WHERE user_id = ? AND endpoint = ? AND idempotency_key = ?
                """, storageUserId, endpoint, key);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "幂等请求不存在或已过期");
        }
        Map<String, Object> row = rows.get(0);
        Instant expiresAt = toInstant(row.get("expires_at"));
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "幂等请求及其事件游标已过期，不能据此创建新的业务运行");
        }
        String status = String.valueOf(row.get("status"));
        String runId = null;
        if (COMPLETED.equals(status) && row.get("response_json") != null) {
            runId = readResponse(String.valueOf(row.get("response_json"))).runId();
        }
        return new StatusView(
                key,
                status,
                String.valueOf(row.get("request_fingerprint")),
                runId,
                toInstant(row.get("created_at")),
                toInstant(row.get("updated_at")),
                expiresAt);
    }

    private Prepared prepareExisting(String userId,
                                     String endpoint,
                                     String key,
                                     String fingerprint) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT request_fingerprint, status, response_json, expires_at
                FROM agent_idempotency_record
                WHERE user_id = ? AND endpoint = ? AND idempotency_key = ?
                """, userId, endpoint, key);
        if (rows.isEmpty()) {
            UUID claimId = claim(userId, endpoint, key, fingerprint);
            if (claimId != null) {
                return new Prepared(userId, endpoint, key, fingerprint, claimId, null);
            }
            throw conflict("幂等请求正在建立，请稍后查询");
        }
        Map<String, Object> row = rows.get(0);
        Instant expiresAt = toInstant(row.get("expires_at"));
        String status = String.valueOf(row.get("status"));
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            if (IN_PROGRESS.equals(status) || AMBIGUOUS.equals(status)) {
                jdbcTemplate.update("""
                        UPDATE agent_idempotency_record
                        SET status = ?, updated_at = now()
                        WHERE user_id = ? AND endpoint = ? AND idempotency_key = ? AND status = ?
                        """, AMBIGUOUS, userId, endpoint, key, IN_PROGRESS);
                throw conflict("过期请求的执行结果未知，禁止自动重试；请人工核对后使用新的业务键");
            }
            int deleted = jdbcTemplate.update("""
                    DELETE FROM agent_idempotency_record
                    WHERE user_id = ? AND endpoint = ? AND idempotency_key = ? AND expires_at <= now()
                    """, userId, endpoint, key);
            UUID claimId = deleted > 0 ? claim(userId, endpoint, key, fingerprint) : null;
            if (claimId != null) {
                return new Prepared(userId, endpoint, key, fingerprint, claimId, null);
            }
            return prepareExisting(userId, endpoint, key, fingerprint);
        }
        if (!fingerprint.equals(String.valueOf(row.get("request_fingerprint")))) {
            throw conflict("同一 Idempotency-Key 不能对应不同请求体");
        }
        if (COMPLETED.equals(status)) {
            return new Prepared(userId, endpoint, key, fingerprint, null,
                    readResponse(String.valueOf(row.get("response_json"))));
        }
        if (AMBIGUOUS.equals(status)) {
            throw conflict("上次请求结果未知，禁止自动重试；请按幂等键查询或人工核对");
        }
        throw conflict("相同幂等请求仍在处理中");
    }

    private UUID claim(String userId, String endpoint, String key, String fingerprint) {
        UUID claimId = UUID.randomUUID();
        int inserted = jdbcTemplate.update("""
                INSERT INTO agent_idempotency_record
                    (user_id, endpoint, idempotency_key, request_fingerprint, claim_id, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, endpoint, idempotency_key) DO NOTHING
                """, userId, endpoint, key, fingerprint, claimId, IN_PROGRESS,
                Timestamp.from(Instant.now().plus(ttl)));
        return inserted == 1 ? claimId : null;
    }

    private Outcome executeClaimed(String userId,
                                   String endpoint,
                                   String key,
                                   String fingerprint,
                                   UUID claimId,
                                   Supplier<AgentResearchResponse> action) {
        try {
            AgentResearchResponse response = action.get();
            String json = objectMapper.writeValueAsString(response);
            int updated = jdbcTemplate.update("""
                    UPDATE agent_idempotency_record
                    SET status = ?, response_json = ?, updated_at = now()
                    WHERE user_id = ? AND endpoint = ? AND idempotency_key = ?
                      AND request_fingerprint = ? AND claim_id = ? AND status = ?
                    """, COMPLETED, json, userId, endpoint, key, fingerprint, claimId, IN_PROGRESS);
            if (updated != 1) {
                throw new IllegalStateException("幂等记录状态更新失败");
            }
            return new Outcome(response, false);
        } catch (RuntimeException failure) {
            jdbcTemplate.update("""
                    UPDATE agent_idempotency_record
                    SET status = ?, updated_at = now()
                    WHERE user_id = ? AND endpoint = ? AND idempotency_key = ?
                      AND claim_id = ? AND status = ?
                    """, AMBIGUOUS, userId, endpoint, key, claimId, IN_PROGRESS);
            throw failure;
        } catch (Exception failure) {
            jdbcTemplate.update("""
                    UPDATE agent_idempotency_record
                    SET status = ?, updated_at = now()
                    WHERE user_id = ? AND endpoint = ? AND idempotency_key = ?
                      AND claim_id = ? AND status = ?
                    """, AMBIGUOUS, userId, endpoint, key, claimId, IN_PROGRESS);
            throw new IllegalStateException("幂等响应序列化失败", failure);
        }
    }

    private String fingerprint(AgentResearchRequest request) {
        try {
            return ToolArgumentFingerprint.sha256(objectMapper.writeValueAsString(
                    new FingerprintPayload(
                            request.question(), request.sessionId() == null ? "" : request.sessionId())));
        } catch (Exception failure) {
            throw new IllegalArgumentException("请求无法规范化", failure);
        }
    }

    private void validateKey(String key) {
        if (!key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key 需为 8-128 位安全字符");
        }
    }

    private AgentResearchResponse readResponse(String json) {
        try {
            return objectMapper.readValue(json, AgentResearchResponse.class);
        } catch (Exception failure) {
            throw new IllegalStateException("幂等响应无法读取", failure);
        }
    }

    private Instant toInstant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        return null;
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record Outcome(AgentResearchResponse response, boolean replayed) {
    }

    public record Prepared(
            String storageUserId,
            String endpoint,
            String idempotencyKey,
            String requestFingerprint,
            UUID claimId,
            AgentResearchResponse replayResponse
    ) {
    }

    private record FingerprintPayload(String question, String sessionId) {
    }

    public record StatusView(
            String idempotencyKey,
            String status,
            String requestFingerprint,
            String runId,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) {
    }
}
