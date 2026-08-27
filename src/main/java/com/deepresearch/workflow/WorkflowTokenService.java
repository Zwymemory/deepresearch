package com.deepresearch.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Separate short-lived service and MCP delegation JWTs; user API JWTs are never accepted here. */
@Service
public class WorkflowTokenService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final ObjectMapper objectMapper;
    private final byte[] internalSecret;
    private final byte[] mcpSecret;

    public WorkflowTokenService(
            ObjectMapper objectMapper,
            @Value("${deepresearch.workflow.internal-jwt-secret}") String internalSecret,
            @Value("${deepresearch.workflow.mcp-jwt-secret}") String mcpSecret,
            @Value("${deepresearch.security.jwt-secret}") String apiSecret,
            @Value("${deepresearch.workflow.enabled:false}") boolean workflowEnabled) {
        this.objectMapper = objectMapper;
        this.internalSecret = secret(internalSecret, "internal-jwt-secret");
        this.mcpSecret = secret(mcpSecret, "mcp-jwt-secret");
        if (workflowEnabled && (internalSecret.equals(mcpSecret)
                || internalSecret.equals(apiSecret) || mcpSecret.equals(apiSecret))) {
            throw new IllegalStateException("启用 workflow 时 API、internal 与 MCP JWT 密钥必须彼此不同");
        }
    }

    public Issued issueServiceToken(String serviceId, long ttlSeconds) {
        String subject = safe(serviceId, "serviceId", 128);
        long now = Instant.now().getEpochSecond();
        long ttl = Math.max(1, Math.min(ttlSeconds, 60));
        Map<String, Object> claims = baseClaims("deepresearch-workflow", "deepresearch-internal", subject, now, ttl);
        return encode(claims, internalSecret);
    }

    public String authenticateService(String token) {
        Map<String, Object> claims = decode(token, internalSecret,
                "deepresearch-workflow", "deepresearch-internal");
        long iat = number(claims.get("iat"));
        long exp = number(claims.get("exp"));
        if (exp - iat > 60) {
            throw unauthorized("internal token 有效期过长");
        }
        return safe(String.valueOf(claims.getOrDefault("sub", "")), "serviceId", 128);
    }

    public Issued issueDelegation(String tenantId, String userId, String runId, String grantId,
                                  String taskId, String claimToken, List<String> scopes, long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        long ttl = Math.max(1, Math.min(ttlSeconds, 90));
        String safeTenant = safe(tenantId, "tenantId", 64);
        String safeUser = safe(userId, "userId", 64);
        Map<String, Object> claims = baseClaims("deepresearch-api", "deepresearch-mcp",
                safeTenant + ":" + safeUser, now, ttl);
        claims.put("tenantId", safeTenant);
        claims.put("run_id", safe(runId, "runId", 64));
        claims.put("grant_id", safe(grantId, "grantId", 64));
        claims.put("task_id", safe(taskId, "taskId", 64));
        claims.put("claim_token", safeClaimToken(claimToken));
        claims.put("scp", scopes == null ? List.of() : scopes);
        claims.put("scopes", scopes == null ? List.of() : scopes);
        return encode(claims, mcpSecret);
    }

    public Delegation authenticateDelegation(String token) {
        Map<String, Object> claims = decode(token, mcpSecret, "deepresearch-api", "deepresearch-mcp");
        long iat = number(claims.get("iat"));
        long exp = number(claims.get("exp"));
        if (exp - iat > 90) {
            throw unauthorized("delegation token 有效期过长");
        }
        List<String> scopes = objectMapper.convertValue(
                claims.getOrDefault("scopes", claims.getOrDefault("scp", List.of())),
                new TypeReference<>() {
                });
        String subject = safe(String.valueOf(claims.get("sub")), "subject", 160);
        String[] subjectParts = subject.split(":", 2);
        if (subjectParts.length != 2) {
            throw unauthorized("delegation subject 格式无效");
        }
        String tenantId = safe(String.valueOf(claims.get("tenantId")), "tenantId", 64);
        if (!tenantId.equals(subjectParts[0])) {
            throw unauthorized("delegation tenant 与 subject 不一致");
        }
        return new Delegation(
                tenantId,
                safe(subjectParts[1], "userId", 64),
                safe(String.valueOf(claims.get("run_id")), "runId", 64),
                safe(String.valueOf(claims.get("grant_id")), "grantId", 64),
                safe(String.valueOf(claims.get("task_id")), "taskId", 64),
                safeClaimToken(String.valueOf(claims.get("claim_token"))),
                scopes.stream().map(scope -> safe(scope, "scope", 64)).distinct().toList(), exp);
    }

    private Map<String, Object> baseClaims(String issuer, String audience, String subject,
                                           long now, long ttl) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("sub", subject);
        claims.put("iat", now);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("exp", now + ttl);
        return claims;
    }

    private Issued encode(Map<String, Object> claims, byte[] signingSecret) {
        try {
            String header = ENCODER.encodeToString(objectMapper.writeValueAsBytes(
                    Map.of("alg", "HS256", "typ", "JWT")));
            String payload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            String signed = header + "." + payload;
            String token = signed + "." + sign(signed, signingSecret);
            return new Issued(token, number(claims.get("exp")));
        } catch (Exception failure) {
            throw new IllegalStateException("workflow JWT 序列化失败", failure);
        }
    }

    private Map<String, Object> decode(String token, byte[] signingSecret,
                                       String issuer, String audience) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.");
            if (parts.length != 3) {
                throw unauthorized("非法 workflow token");
            }
            Map<String, Object> header = objectMapper.readValue(DECODER.decode(parts[0]), new TypeReference<>() {
            });
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
                throw unauthorized("workflow token 算法或类型无效");
            }
            String signed = parts[0] + "." + parts[1];
            if (!constantTimeEquals(sign(signed, signingSecret), parts[2])) {
                throw unauthorized("workflow token 签名无效");
            }
            Map<String, Object> claims = objectMapper.readValue(DECODER.decode(parts[1]), new TypeReference<>() {
            });
            if (!issuer.equals(claims.get("iss")) || !audienceMatches(claims.get("aud"), audience)) {
                throw unauthorized("workflow token issuer 或 audience 无效");
            }
            if (Instant.now().getEpochSecond() >= number(claims.get("exp"))) {
                throw unauthorized("workflow token 已过期");
            }
            return claims;
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unauthorized("workflow token 解析失败");
        }
    }

    private boolean audienceMatches(Object raw, String expected) {
        return expected.equals(raw) || (raw instanceof List<?> list && list.contains(expected));
    }

    private String sign(String content, byte[] signingSecret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
        return ENCODER.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < left.length(); i++) {
            result |= left.charAt(i) ^ right.charAt(i);
        }
        return result == 0;
    }

    private byte[] secret(String value, String name) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("deepresearch.workflow." + name + " 至少需要 32 字节");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String safe(String value, String name, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max || !normalized.matches("[A-Za-z0-9_.@:-]{1," + max + "}")) {
            throw unauthorized("非法 " + name);
        }
        return normalized;
    }

    private long number(Object raw) {
        return raw instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(raw));
    }

    private String safeClaimToken(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim()).toString();
        } catch (IllegalArgumentException failure) {
            throw unauthorized("非法 claimToken");
        }
    }

    private ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(UNAUTHORIZED, reason);
    }

    public record Issued(String token, long expiresAt) {
    }

    public record Delegation(String tenantId, String userId, String runId, String grantId,
                             String taskId, String claimToken, List<String> scopes, long expiresAt) {
        public String storageUserId() {
            return tenantId + ":" + userId;
        }
    }
}
