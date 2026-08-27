package com.deepresearch.web;

import com.deepresearch.security.JwtTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * 本地开发用 token 签发接口。
 *
 * 生产环境通常由 SSO/OAuth2 网关签发 JWT，本接口可通过网关或配置关闭。
 */
@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtTokenService jwtTokenService;
    private final boolean devTokenEnabled;
    private final boolean devTokenAllowAdmin;

    public AuthController(JwtTokenService jwtTokenService,
                          @Value("${deepresearch.security.dev-token-enabled:false}") boolean devTokenEnabled,
                          @Value("${deepresearch.security.dev-token-allow-admin:false}") boolean devTokenAllowAdmin) {
        this.jwtTokenService = jwtTokenService;
        this.devTokenEnabled = devTokenEnabled;
        this.devTokenAllowAdmin = devTokenAllowAdmin;
    }

    @PostMapping("/dev-token")
    public JwtTokenService.IssuedToken devToken(@RequestBody @Valid DevTokenRequest request) {
        if (!devTokenEnabled) {
            throw new org.springframework.web.server.ResponseStatusException(FORBIDDEN, "dev-token 已关闭");
        }
        List<String> roles = normalizeRoles(request.roles());
        if (roles.contains("ADMIN") && !devTokenAllowAdmin) {
            throw new org.springframework.web.server.ResponseStatusException(
                    FORBIDDEN, "dev-token 不允许匿名签发管理员角色");
        }
        return jwtTokenService.issue(
                request.tenantId(),
                request.userId(),
                roles,
                request.ttlSeconds()
        );
    }

    private List<String> normalizeRoles(List<String> requested) {
        List<String> roles = requested == null || requested.isEmpty()
                ? List.of("USER")
                : requested.stream()
                .map(role -> role == null ? "" : role.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (roles.isEmpty() || roles.stream().anyMatch(role -> !List.of("USER", "ADMIN").contains(role))) {
            throw new org.springframework.web.server.ResponseStatusException(FORBIDDEN, "dev-token 角色不合法");
        }
        return roles;
    }

    public record DevTokenRequest(
            @NotBlank String tenantId,
            @NotBlank String userId,
            List<String> roles,
            Long ttlSeconds
    ) {
    }
}
