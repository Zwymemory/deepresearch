package com.deepresearch.web;

import com.deepresearch.service.DifyRetrievalService;
import com.deepresearch.web.dto.DifyRetrievalRequest;
import com.deepresearch.web.dto.DifyRetrievalResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dify 集成边界。该路径沿用 /api/** 的 Bearer JWT 认证策略。
 */
@RestController
@RequestMapping("/api/integrations/dify")
public class DifyIntegrationController {

    private final DifyRetrievalService retrievalService;

    public DifyIntegrationController(DifyRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/retrieve")
    public DifyRetrievalResponse retrieve(@RequestBody @Valid DifyRetrievalRequest request) {
        return retrievalService.retrieve(request);
    }
}
