package com.deepresearch.service;

import com.deepresearch.web.dto.AgentResearchResponse;

/** Harness 内部运行结果；公开 Agent API 不会看到 evidence artifact。 */
record AgentEvaluationRun(AgentResearchResponse response, AgentEvaluationArtifact artifact) {
}
