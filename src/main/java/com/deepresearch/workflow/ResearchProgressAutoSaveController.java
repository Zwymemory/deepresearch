package com.deepresearch.workflow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="deepresearch.workflow.enabled",havingValue="true")
@RequestMapping("/api/research/agents")
public class ResearchProgressAutoSaveController {
    private final ResearchProgressAutoSaveService saves;
    public ResearchProgressAutoSaveController(ResearchProgressAutoSaveService saves) {this.saves=saves;}
    @GetMapping("/{runId}/progress-save") public JsonNode status(@PathVariable String runId) {return saves.view(runId);}
}
