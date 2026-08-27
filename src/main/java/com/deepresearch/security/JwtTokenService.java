package com.deepresearch.security;

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
import java.util.Locale;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * W10.6 production：最小可运行的 HS256 JWT 服务。
 *
 * 生产环境可以替换为 OAuth2 Resource Server / SSO 网关；这里保留标准 JWT 结构，便于本地演示。
 */
@Service
public class JwtTokenService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long defaultTtlSeconds;

    public JwtTokenService(ObjectMapper objectMapper,
                           @Value("${deepresearch.security.jwt-secret}") String jwtSecret,
                           @Value("${deepresearch.security.token-ttl-seconds:7200}") long defaultTtlSeconds) {
        this.objectMapper = objectMapper;
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("deepresearch.security.jwt-secret 至少需要 32 字节");
        }
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public IssuedToken issue(String tenantId, String userId, List<String> roles, Long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        long ttl = ttlSeconds == null || ttlSeconds <= 0 ? defaultTtlSeconds : Math.min(ttlSeconds, 86_400);
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "deepresearch");
        claims.put("aud", "deepresearch-api");
        claims.put("sub", sanitize(userId, "userId"));
        claims.put("tenantId", sanitize(tenantId, "tenantId"));
        claims.put("roles", normalizeRoles(roles));
        claims.put("iat", now);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("exp", now + ttl);

        String headerPart = encodeJson(header);
        String payloadPart = encodeJson(claims);
        String signature = sign(headerPart + "." + payloadPart);
        String token = headerPart + "." + payloadPart + "." + signature;
        return new IssuedToken(token, "Bearer " + token, now + ttl);
    }

    public AuthPrincipal authenticate(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw unauthorized("非法 token");
            }
            String signedContent = parts[0] + "." + parts[1];
            Map<String, Object> header = objectMapper.readValue(
                    URL_DECODER.decode(parts[0]),
                    new TypeReference<>() {
                    }
            );
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
                throw unauthorized("token 算法或类型无效");
            }
            String expected = sign(signedContent);
            if (!constantTimeEquals(expected, parts[2])) {
                throw unauthorized("token 签名无效");
            }
            Map<String, Object> claims = objectMapper.readValue(
                    URL_DECODER.decode(parts[1]),
                    new TypeReference<>() {
                    }
            );
            long exp = numberClaim(claims.get("exp"));
            if (Instant.now().getEpochSecond() >= exp) {
                throw unauthorized("token 已过期");
            }
            if (!"deepresearch".equals(claims.get("iss"))) {
                throw unauthorized("token 发行者无效");
            }
            Object audience = claims.get("aud");
            boolean audienceMatches = "deepresearch-api".equals(audience)
                    || (audience instanceof List<?> list && list.contains("deepresearch-api"));
            if (!audienceMatches) {
                throw unauthorized("token 受众无效");
            }
            String tenantId = sanitize(String.valueOf(claims.getOrDefault("tenantId", "")), "tenantId");
            String userId = sanitize(String.valueOf(claims.getOrDefault("sub", "")), "userId");
            List<String> roles = objectMapper.convertValue(claims.getOrDefault("roles", List.of("USER")),
                    new TypeReference<>() {
                    });
            return new AuthPrincipal(tenantId, userId, normalizeRoles(roles));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized("token 解析失败");
        }
    }

    private String encodeJson(Object value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("JWT JSON 序列化失败", e);
        }
    }

    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private long numberClaim(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String sanitize(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[A-Za-z0-9_.@-]{1,64}")) {
            throw unauthorized("非法 " + field);
        }
        return value;
    }

    private List<String> normalizeRoles(List<String> rawRoles) {
        List<String> roles = rawRoles == null || rawRoles.isEmpty() ? List.of("USER") : rawRoles;
        List<String> normalized = roles.stream()
                .map(role -> role == null ? "" : role.trim().toUpperCase(Locale.ROOT))
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .distinct()
                .toList();
        if (normalized.isEmpty()
                || normalized.stream().anyMatch(role -> !List.of("USER", "ADMIN").contains(role))) {
            throw unauthorized("token 角色无效");
        }
        return normalized;
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(UNAUTHORIZED, message);
    }

    public record IssuedToken(String token, String authorizationHeader, long expiresAt) {
    }
}
