package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import static com.deepresearch.workflow.ResearchProgressSelectionDtos.*;

@RestController
public class LearningNotesController {
    private final LearningNotesService notes;
    private final WorkflowAccessService access;
    public LearningNotesController(LearningNotesService notes,WorkflowAccessService access){this.notes=notes;this.access=access;}
    @GetMapping("/api/research/projects/{project}/learning-notes")
    public JsonNode list(@PathVariable String project){return notes.list(project);}
    @GetMapping("/api/research/projects/{project}/learning-notes/sources/{run}")
    public JsonNode source(@PathVariable String project,@PathVariable String run){return notes.source(project,run);}
    public record Correction(String note) {}
    @PatchMapping("/api/research/projects/{project}/learning-notes/{topic}")
    public JsonNode correct(@PathVariable String project,@PathVariable String topic,@RequestBody Correction body){return notes.correct(project,topic,body.note());}
    @DeleteMapping("/api/research/projects/{project}/learning-notes/{topic}")
    public JsonNode delete(@PathVariable String project,@PathVariable String topic){return notes.delete(project,topic);}
    @GetMapping("/api/research/agents/{run}/learning-notes")
    public JsonNode used(@PathVariable String run){return notes.used(run);}
    @PostMapping("/internal/agent/memory/learning/validate")
    public ValidationResult validate(@RequestHeader("Authorization") String token,@RequestBody ValidationRequest body){access.authenticateInternal(token);return notes.validate(body);}
}
