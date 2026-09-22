package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Typed evidence boundary for the Dify tool endpoint. Implementations must scope
 * dataset access using the supplied Java run owner, not a tenant supplied by Dify.
 */
public interface DifyKbToolGateway {

    List<Evidence> search(AuthPrincipal owner, String query);

    record Evidence(String citationId, String title, String content) {}

    /** Task A's RAGFlow gateway replaces this bean during integration. */
    @Configuration(proxyBeanMethods = false)
    class UnavailableConfiguration {
        @Bean
        @ConditionalOnMissingBean(DifyKbToolGateway.class)
        DifyKbToolGateway unavailableDifyKbToolGateway() {
            return (owner, query) -> {
                throw new IllegalStateException("Dify KB gateway 尚未连接到 RAGFlow");
            };
        }
    }
}
