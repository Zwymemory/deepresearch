package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

/** A owns route allowance and delegated read/check/publish scope, B owns the evidence API. */
@RestController
@RequestMapping("/internal/agent/evidence")
@ConditionalOnProperty(name = "deepresearch.agent.evidence.enabled", havingValue = "true")
public class EvidenceController {
    private final EvidenceService service;
    public EvidenceController(EvidenceService service) { this.service = service; }
    @ExceptionHandler(EvidenceException.class)
    public ResponseEntity<Map<String,String>> rejected(EvidenceException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("errorCode", failure.code()));
    }
    @PostMapping("/read") public JsonNode read(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody EvidenceDtos.ReadRequest body) { return service.read(auth, body); }
    @PostMapping("/checks/prepare") public EvidenceDtos.PreparedCheck prepare(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody EvidenceDtos.PrepareRequest body) { return service.prepare(auth, body); }
    @PostMapping("/checks/complete") public EvidenceDtos.RecordResult complete(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody EvidenceDtos.CompleteRequest body) { return service.complete(auth, body); }
    @PostMapping("/packets") public JsonNode packet(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody EvidenceDtos.PacketRequest body) { return service.packet(auth, body); }
    @PostMapping("/publish") public JsonNode publish(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody EvidenceDtos.PublishRequest body) { return service.publish(auth, body); }
    @PostMapping("/investigations") public JsonNode investigation(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody EvidenceDtos.InvestigationRequest body) { return service.investigation(auth, body); }
    @PostMapping("/reports") public JsonNode report(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody EvidenceDtos.ReportRequest body) { return service.report(auth, body); }
}
