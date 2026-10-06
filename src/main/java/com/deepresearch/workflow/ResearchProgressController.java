package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/research")
public class ResearchProgressController {
    private final ResearchProgressService progress;
    public ResearchProgressController(ResearchProgressService progress) { this.progress=progress; }
    @GetMapping("/agents/{runId}/progress-project")
    public JsonNode project(@PathVariable String runId) { return progress.projectForRun(runId); }
    @GetMapping("/progress")
    public JsonNode list() { return progress.list(); }
    @PutMapping("/projects/{projectId}/progress/runs/{runId}")
    public JsonNode save(@PathVariable String projectId,@PathVariable String runId) { return progress.save(projectId,runId); }
    @GetMapping("/projects/{projectId}/progress/runs/{runId}")
    public JsonNode read(@PathVariable String projectId,@PathVariable String runId) { return progress.read(projectId,runId); }
    public record Correction(String note) {}
    @PatchMapping("/projects/{projectId}/progress/runs/{runId}")
    public JsonNode correct(@PathVariable String projectId,@PathVariable String runId,@RequestBody Correction correction) {
        return progress.correct(projectId,runId,correction.note());
    }
    @DeleteMapping("/projects/{projectId}/progress/runs/{runId}")
    public Map<String,Object> delete(@PathVariable String projectId,@PathVariable String runId) {
        return Map.of("run_id",runId,"deleted",progress.delete(projectId,runId));
    }
    @PostMapping("/projects/{projectId}/resume-context")
    public JsonNode newSession(@PathVariable String projectId) { return progress.resumeInNewSession(projectId); }
    @GetMapping("/projects/{projectId}/resume-context")
    public JsonNode resume(@PathVariable String projectId,@RequestParam String sessionId) { return progress.resume(projectId,sessionId); }
}
