package com.deepresearch.web;

import com.deepresearch.workflow.DifyToolDtos;
import com.deepresearch.workflow.DifyToolService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The only Java tool surface called from Dify Workflow HTTP nodes. */
@RestController
@RequestMapping("/internal/dify/tools")
public class DifyToolController {

    private final DifyToolService service;

    public DifyToolController(DifyToolService service) {
        this.service = service;
    }

    @PostMapping("/{tool}")
    public DifyToolDtos.Response execute(@PathVariable String tool,
                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody @Valid DifyToolDtos.Request request) {
        return service.execute(authorization, tool, request);
    }
}
