package com.deepresearch.evidence;

import com.deepresearch.service.RagflowClient;
import com.deepresearch.service.RagflowDocumentRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in; absent control-plane adapter denies all operations instead of granting fixture scopes. */
@Configuration
@ConditionalOnProperty(name = "deepresearch.agent.evidence.enabled", havingValue = "true")
public class EvidenceConfiguration {
    @Bean SafeWebReader safeWebReader(@Value("${deepresearch.agent.evidence.web.dns-mode:system}") String mode) {
        return switch (mode) {
            case "system" -> new SafeWebReader();
            case "google-doh" -> new SafeWebReader(new GoogleDohResolver(), new PinnedHttpTransport());
            default -> throw new IllegalArgumentException("Unsupported web source DNS mode");
        };
    }
    @Bean EvidenceStore evidenceStore(JdbcTemplate db, PlatformTransactionManager tx) {
        return new JdbcEvidenceStore(db, new TransactionTemplate(tx));
    }
    @Bean EvidenceService evidenceService(ObjectProvider<EvidenceAuthority> authorities, EvidenceStore store,
                                         RagflowClient ragflow, RagflowDocumentRegistry registry, SafeWebReader web) {
        var authority = authorities.getIfAvailable(EvidenceAuthority::denyAll);
        return new EvidenceService(authority, store, new ManagedSourceReader(web, ragflow, registry, authority));
    }
}
