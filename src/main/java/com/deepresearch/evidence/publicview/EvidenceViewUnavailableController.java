package com.deepresearch.evidence.publicview;

import com.deepresearch.service.UserContextService;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** Disabled capability has an explicit authenticated response, without any database dependency. */
@RestController
@Conditional(EvidenceViewUnavailableController.Disabled.class)
public class EvidenceViewUnavailableController {
    private final UserContextService users;
    public EvidenceViewUnavailableController(UserContextService users) { this.users = users; }

    @GetMapping("/api/research/workflows/{runId}/evidence")
    public ResponseEntity<Map<String, String>> unavailable() {
        users.currentPrincipalRequired();
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(Map.of("errorCode", "EVIDENCE_VIEW_DISABLED"));
    }

    public static final class Disabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return !("true".equalsIgnoreCase(env.getProperty("deepresearch.workflow.enabled"))
                    && "true".equalsIgnoreCase(env.getProperty("deepresearch.agent.evidence.enabled")));
        }
    }
}
