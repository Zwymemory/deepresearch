package com.deepresearch.evidence.publicview;

import com.deepresearch.service.UserContextService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class EvidenceViewController {
    private final EvidenceViewService service;
    private final UserContextService users;
    public EvidenceViewController(EvidenceViewService service, UserContextService users) {
        this.service = service; this.users = users;
    }
    @GetMapping("/api/research/workflows/{runId}/evidence")
    public ResponseEntity<EvidenceViewDtos.View> get(@PathVariable String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.read(runId, users.currentPrincipalRequired()));
    }
    @ExceptionHandler(EvidenceViewService.IntegrityFailure.class)
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                .body(Map.of("errorCode", "EVIDENCE_VIEW_INTEGRITY_INVALID"));
    }
}
