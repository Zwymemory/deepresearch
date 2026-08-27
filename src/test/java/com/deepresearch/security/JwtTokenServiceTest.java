package com.deepresearch.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String SECRET = "jwt-test-secret-with-at-least-32-bytes";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtTokenService tokenService = new JwtTokenService(objectMapper, SECRET, 7200);

    @Test
    void issuesAndAuthenticatesTenantScopedPrincipal() {
        JwtTokenService.IssuedToken issued = tokenService.issue("tenant-1", "user-1", List.of("USER", "ADMIN"), null);

        AuthPrincipal principal = tokenService.authenticate(issued.token());

        assertThat(issued.authorizationHeader()).isEqualTo("Bearer " + issued.token());
        assertThat(principal.tenantId()).isEqualTo("tenant-1");
        assertThat(principal.userId()).isEqualTo("user-1");
        assertThat(principal.storageUserId()).isEqualTo("tenant-1:user-1");
        assertThat(principal.hasRole("admin")).isTrue();
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        String token = tokenService.issue("tenant-1", "user-1", List.of("USER"), 1L).token();
        Thread.sleep(1_100);

        assertThatThrownBy(() -> tokenService.authenticate(token))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("token 已过期");
    }

    @Test
    void rejectsTamperedSignature() {
        String token = tokenService.issue("tenant-1", "user-1", List.of("USER"), null).token();
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;

        assertThatThrownBy(() -> tokenService.authenticate(tampered))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("token 签名无效");
    }

    @Test
    void rejectsMissingTenantOrUserClaimEvenWithValidSignature() throws Exception {
        String valid = tokenService.issue("tenant-1", "user-1", List.of("USER"), null).token();

        assertThatThrownBy(() -> tokenService.authenticate(withoutClaim(valid, "tenantId")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("非法 tenantId");
        assertThatThrownBy(() -> tokenService.authenticate(withoutClaim(valid, "sub")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("非法 userId");
    }

    @Test
    void rejectsWrongIssuerOrAudienceEvenWithValidSignature() throws Exception {
        String valid = tokenService.issue("tenant-1", "user-1", List.of("USER"), null).token();

        assertThatThrownBy(() -> tokenService.authenticate(withClaim(valid, "iss", "attacker")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("发行者无效");
        assertThatThrownBy(() -> tokenService.authenticate(withClaim(valid, "aud", "other-api")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("受众无效");
    }

    @Test
    void rejectsUnsafeShortSigningSecret() {
        assertThatThrownBy(() -> new JwtTokenService(objectMapper, "short-secret", 7200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少需要 32 字节");
    }

    @Test
    void normalizesRolePrefixForEndpointAndToolPolicyConsistency() {
        String token = tokenService.issue("tenant-1", "admin-1", List.of("ROLE_ADMIN"), null).token();

        assertThat(tokenService.authenticate(token).roles()).containsExactly("ADMIN");
        assertThat(tokenService.authenticate(token).hasRole("ADMIN")).isTrue();
    }

    private String withoutClaim(String token, String claim) throws Exception {
        String[] parts = token.split("\\.");
        Map<String, Object> claims = objectMapper.readValue(
                Base64.getUrlDecoder().decode(parts[1]),
                new TypeReference<>() {
                }
        );
        Map<String, Object> changed = new LinkedHashMap<>(claims);
        changed.remove(claim);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(changed));
        String signedContent = parts[0] + "." + payload;
        return signedContent + "." + sign(signedContent);
    }

    private String withClaim(String token, String claim, Object value) throws Exception {
        String[] parts = token.split("\\.");
        Map<String, Object> claims = objectMapper.readValue(
                Base64.getUrlDecoder().decode(parts[1]),
                new TypeReference<>() {
                }
        );
        Map<String, Object> changed = new LinkedHashMap<>(claims);
        changed.put(claim, value);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(changed));
        String signedContent = parts[0] + "." + payload;
        return signedContent + "." + sign(signedContent);
    }

    private String sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
