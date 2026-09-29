package com.deepresearch.evidence;

import com.deepresearch.service.RagflowClient;
import com.deepresearch.service.RagflowDocumentRegistry;
import org.springframework.beans.factory.ObjectProvider;
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
    @Bean EvidenceStore evidenceStore(JdbcTemplate db, PlatformTransactionManager tx) {
        return new JdbcEvidenceStore(db, new TransactionTemplate(tx));
    }
    @Bean EvidenceService evidenceService(ObjectProvider<EvidenceAuthority> authorities, EvidenceStore store,
                                         RagflowClient ragflow, RagflowDocumentRegistry registry) {
        var authority = authorities.getIfAvailable(EvidenceAuthority::denyAll);
        return new EvidenceService(authority, store, new ManagedSourceReader(new SafeWebReader(), ragflow, registry, authority));
    }
}
