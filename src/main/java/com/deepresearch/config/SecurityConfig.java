package com.deepresearch.config;

import com.deepresearch.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * W10.6 production：Agent 有状态接口启用认证，管理接口启用 ADMIN 角色。
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtAuthenticationFilter,
                                            @Value("${deepresearch.security.mcp-public:false}") boolean mcpPublic) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"error\":\"需要登录\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"error\":\"权限不足\"}");
                        })
                )
                .authorizeHttpRequests(auth -> {
                    // SSE completion re-enters the filter chain as ASYNC after the
                    // initial authenticated REQUEST. No new client request is admitted here.
                    auth.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll();
                    auth.requestMatchers("/", "/demo.html", "/api/ping", "/actuator/health", "/error").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/auth/dev-token").permitAll();
                    auth.requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN");
                    // 仅列出的私网端点在 controller/service 验证独立服务身份；其余 internal 路径拒绝。
                    auth.requestMatchers(HttpMethod.POST,
                            "/internal/workflow-grants/*/token",
                            "/internal/research/workflows/*/finalize",
                            "/internal/dify/tools/kb_search",
                            "/internal/dify/tools/web_search",
                            "/internal/dify/tools/calculator",
                            "/internal/agent/evidence/read",
                            "/internal/agent/evidence/checks/prepare",
                            "/internal/agent/evidence/checks/complete",
                            "/internal/agent/evidence/packets",
                            "/internal/agent/evidence/investigations",
                            "/internal/agent/evidence/reports",
                            "/internal/agent/evidence/publish",
                            "/internal/agent/publication",
                            "/internal/agent/memory/validate",
                            "/internal/agent/memory/recall/validate").permitAll();
                    auth.requestMatchers("/internal/**").denyAll();
                    // Dify 只能以普通 API 身份读取已裁剪证据；禁止匿名访问。
                    auth.requestMatchers(HttpMethod.POST,
                            "/api/integrations/dify/retrieve").hasAnyRole("USER", "ADMIN");
                    auth.requestMatchers("/api/kb/**", "/api/eval/**", "/api/mcp/**",
                            "/api/agent/bad-cases", "/api/agent/report").hasRole("ADMIN");
                    if (mcpPublic) {
                        auth.requestMatchers("/mcp/**").permitAll();
                    } else {
                        // JwtAuthenticationFilter 对该路径只接受 aud=deepresearch-mcp 的短期 delegation。
                        auth.requestMatchers("/mcp/**").authenticated();
                    }
                    auth.requestMatchers("/api/**", "/actuator/info").authenticated();
                    auth.anyRequest().denyAll();
                })
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("DeepResearch uses stateless bearer tokens");
        };
    }
}
