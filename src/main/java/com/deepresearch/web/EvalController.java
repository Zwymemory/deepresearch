package com.deepresearch.web;

import com.deepresearch.service.AgentHarnessService;
import com.deepresearch.service.BadCaseRegressionService;
import com.deepresearch.service.RetrievalEvalService;
import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentHarnessResponse;
import com.deepresearch.web.dto.RetrievalEvalRequest;
import com.deepresearch.web.dto.RetrievalEvalResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 评测接口。
 *
 * 目前先评估检索召回与排序，不评估最终 LLM 生成质量。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final RetrievalEvalService retrievalEvalService;
    private final AgentHarnessService agentHarnessService;
    private final BadCaseRegressionService badCaseRegressionService;

    public EvalController(RetrievalEvalService retrievalEvalService,
                          AgentHarnessService agentHarnessService,
                          BadCaseRegressionService badCaseRegressionService) {
        this.retrievalEvalService = retrievalEvalService;
        this.agentHarnessService = agentHarnessService;
        this.badCaseRegressionService = badCaseRegressionService;
    }

    @PostMapping("/retrieval")
    public RetrievalEvalResponse retrieval(@RequestBody(required = false) RetrievalEvalRequest request) {
        return retrievalEvalService.evaluate(request);
    }

    @PostMapping("/agent")
    public AgentHarnessResponse agent(@RequestBody(required = false) AgentHarnessRequest request) {
        return agentHarnessService.evaluate(request);
    }

    @PostMapping(value = "/agent/report", produces = MediaType.TEXT_HTML_VALUE)
    public String agentReport(@RequestBody(required = false) AgentHarnessRequest request) {
        return agentHarnessService.evaluateAsHtml(request);
    }

    @PostMapping("/agent/from-bad-cases")
    public AgentHarnessRequest badCasesToRegression(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) List<String> modes) {
        return badCaseRegressionService.convert(limit, modes);
    }
}
