package com.deepresearch.workflow;

import com.deepresearch.agent.ToolArgumentFingerprint;
import com.deepresearch.service.AgentStateService;
import com.deepresearch.service.UserContextService;
import com.deepresearch.workflow.WorkflowDtos.Accepted;
import com.deepresearch.workflow.WorkflowDtos.Cancelled;
import com.deepresearch.workflow.WorkflowDtos.CreateRequest;
import com.deepresearch.workflow.WorkflowDtos.FinalizeRequest;
import com.deepresearch.workflow.WorkflowDtos.FinalizeResponse;
import com.deepresearch.workflow.WorkflowDtos.View;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java control plane for durable workflows; the Python sidecar owns graph execution only. */
@Service
public class WorkflowService {

    private static final String ENDPOINT = "/api/research/workflows";
    private static final String CITATION_CONTRACT = "INDEXED_V1";
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[来源(\\d+)]");
    private final WorkflowRepository repository;
    private final AgentStateService agentStateService;
    private final UserContextService userContextService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration deadline;
    private final String engine;

    @Autowired
    public WorkflowService(WorkflowRepository repository,
                           AgentStateService agentStateService,
                           UserContextService userContextService,
                           ObjectMapper objectMapper,
                           @Value("${deepresearch.workflow.enabled:false}") boolean enabled,
                           @Value("${deepresearch.workflow.deadline:120s}") Duration deadline,
                           @Value("${deepresearch.workflow.engine:langgraph}") String engine) {
        this.repository = repository;
        this.agentStateService = agentStateService;
        this.userContextService = userContextService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.deadline = deadline == null || deadline.isNegative() || deadline.isZero()
                ? Duration.ofSeconds(120) : deadline;
        if (!Set.of("langgraph", "dify").contains(engine)) {
            throw new IllegalArgumentException("deepresearch.workflow.engine 只允许 langgraph 或 dify");
        }
        this.engine = engine;
    }

    /** Keeps existing in-process callers on the legacy engine. */
    public WorkflowService(WorkflowRepository repository, AgentStateService agentStateService,
                           UserContextService userContextService, ObjectMapper objectMapper,
                           boolean enabled, Duration deadline) {
        this(repository, agentStateService, userContextService, objectMapper, enabled, deadline, "langgraph");
    }

    @Transactional
    public Accepted create(CreateRequest request, String idempotencyKey) {
        return createWithEngine(request, idempotencyKey, engine, ENDPOINT);
    }

    @Transactional
    public Accepted createAutonomous(CreateRequest request, String idempotencyKey) {
        return createWithEngine(request, idempotencyKey, "agent", "/api/research/agents");
    }

    private Accepted createWithEngine(CreateRequest request, String idempotencyKey,
                                     String selectedEngine, String endpoint) {
        requireEnabled();
        String userId = userContextService.currentUser();
        String key = validateKey(idempotencyKey);
        List<String> scopes = normalizeScopes(request.requestedTools());
        if ("agent".equals(selectedEngine)) {
            List<String> expanded = new ArrayList<>(scopes);
            if (scopes.contains("kb_search") || scopes.contains("web_search")) {
                expanded.add("read_source");
                expanded.add("check_claims");
            }
            scopes = expanded.stream().sorted().toList();
        }
        String fingerprint = fingerprint(request, scopes);

        WorkflowRepository.RunRow existing = repository.findByIdempotency(userId, endpoint, key).orElse(null);
        if (existing != null) {
            return replay(existing, fingerprint);
        }

        String requestedSession = request.sessionId();
        if (requestedSession == null || requestedSession.isBlank()) {
            requestedSession = "sess-wf-" + ToolArgumentFingerprint.sha256(userId + ":" + key).substring(0, 24);
        }
        AgentStateService.AgentContext context = agentStateService.prepareContext(
                requestedSession, userId, request.question().trim());
        String runId = "wf-" + UUID.randomUUID();
        String grantId = "grant-" + UUID.randomUUID();
        OffsetDateTime deadlineAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plus("agent".equals(selectedEngine) ? Duration.ofSeconds(180) : deadline);
        String contextJson = boundedContext(context);
        WorkflowRepository.NewRun newRun = new WorkflowRepository.NewRun(
                runId, context.sessionId(), userId, request.question().trim(), contextJson,
                endpoint, key, fingerprint, runId,
                "dify".equals(selectedEngine) ? WorkflowStatus.DIFY_DISPATCHING.name() : WorkflowStatus.QUEUED.name(),
                "dify".equals(selectedEngine) ? WorkflowStatus.DIFY_DISPATCHING.name() : WorkflowStatus.QUEUED.name(),
                deadlineAt, scopes, grantId);
        if (repository.insertRun(newRun) == 0) {
            WorkflowRepository.RunRow winner = repository.findByIdempotency(userId, endpoint, key)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "workflow 幂等请求正在建立"));
            return replay(winner, fingerprint);
        }
        repository.insertGrant(new WorkflowRepository.NewGrant(
                grantId, runId, userId, scopes, 1, deadlineAt));
        if ("dify".equals(selectedEngine)) {
            repository.insertDifyMapping(runId);
        }
        repository.insertEvent(runId, "workflow:queued", "SYSTEM", null, "QUEUED",
                writeJson(Map.of("status", newRun.status(), "stage", newRun.stage())));
        return accepted(newRun.runId(), newRun.sessionId(), newRun.status(), newRun.stage(), false);
    }

    public View get(String runId) {
        requireEnabled();
        String userId = userContextService.currentUser();
        WorkflowRepository.RunRow row = owned(runId, userId);
        return view(row);
    }

    public List<WorkflowDtos.Event> eventsAfter(String runId, long afterEventId) {
        String userId = userContextService.currentUser();
        owned(runId, userId);
        return repository.eventsAfter(runId, afterEventId, 200);
    }

    public List<WorkflowDtos.Event> eventsAfterOwned(String runId, String userId, long afterEventId) {
        owned(runId, userId);
        return repository.eventsAfter(runId, afterEventId, 200);
    }

    public WorkflowRepository.RunRow ownedRun(String runId, String userId) {
        return owned(runId, userId);
    }

    public record StreamPermit(String runId,String userId,String tenantId,String ownerId) {}

    public StreamPermit permitStream(String runId) {
        String user= userContextService.currentUser();
        owned(runId,user);
        var identity=repository.agentIdentity(runId);
        return new StreamPermit(runId,user,identity.map(WorkflowRepository.AgentIdentity::tenantId).orElse(null),
                identity.map(WorkflowRepository.AgentIdentity::ownerId).orElse(null));
    }

    public WorkflowRepository.RunRow ownedForStream(StreamPermit permit) {
        var row=repository.findOwned(permit.runId(),permit.userId()).orElseThrow(()->
                new ResponseStatusException(HttpStatus.NOT_FOUND,"workflow 不存在"));
        var identity=repository.agentIdentity(permit.runId());
        if (identity.isPresent() && (!identity.get().tenantId().equals(permit.tenantId())
                || !identity.get().ownerId().equals(permit.ownerId()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"workflow 不存在");
        }
        return row;
    }

    public List<WorkflowDtos.Event> eventsForStream(StreamPermit permit,long cursor) {
        ownedForStream(permit);
        return repository.eventsAfter(permit.runId(),cursor,200);
    }

    @Transactional
    public Cancelled cancel(String runId) {
        requireEnabled();
        String userId = userContextService.currentUser();
        WorkflowRepository.RunRow before = owned(runId, userId);
        WorkflowStatus status = status(before.status());
        if (status.terminal()) {
            return new Cancelled(runId, status.name(), true);
        }
        int changed = repository.cancel(runId, userId);
        if (changed != 1) {
            WorkflowRepository.RunRow raced = owned(runId, userId);
            return new Cancelled(runId, raced.status(), status(raced.status()).terminal());
        }
        repository.revokeGrantForRun(runId);
        repository.insertEvent(runId, "workflow:cancelled", "SYSTEM", null, "CANCELLED",
                writeJson(Map.of("status", "CANCELLED")));
        return new Cancelled(runId, WorkflowStatus.CANCELLED.name(), false);
    }

    @Transactional
    public FinalizeResponse finalizeRun(String runId, FinalizeRequest request) {
        WorkflowStatus requested = terminalStatus(request.status());
        UUID claimToken;
        try {
            claimToken = UUID.fromString(request.claimToken());
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "claimToken 格式无效");
        }
        String answer = request.answer() == null ? "" : request.answer().trim();
        List<String> citations = request.citations() == null ? List.of() : request.citations().stream()
                .map(value -> value == null ? "" : value.trim()).toList();
        if (requested == WorkflowStatus.SUCCEEDED && (answer.isBlank() || citations.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SUCCEEDED 必须包含非空答案和至少一个可信引用");
        }
        if (requested == WorkflowStatus.SUCCEEDED && !validCitationContract(answer, citations)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SUCCEEDED 的 [来源N] 与 citations 必须满足 INDEXED_V1 映射");
        }
        String errorCode = safeError(request.errorCode(), 64);
        String errorMessage = safeError(request.errorMessage(), 500);
        if (requested == WorkflowStatus.FAILED
                && "CITATION_VALIDATION_FAILED".equals(errorCode)
                && (!answer.isBlank() || !citations.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "引用校验失败不得发布候选答案或引用");
        }
        String usageJson = writeJson(request.usage() == null ? Map.of() : request.usage());
        Map<String, Object> finalResponse = new LinkedHashMap<>();
        finalResponse.put("answer", answer);
        finalResponse.put("citations", citations);
        finalResponse.put("citationContract",
                requested == WorkflowStatus.SUCCEEDED ? CITATION_CONTRACT : "NONE");
        finalResponse.put("insufficientEvidence", requested == WorkflowStatus.INSUFFICIENT_EVIDENCE);
        String finalResponseJson = writeJson(finalResponse);
        String finalizeFingerprint = finalizeFingerprint(requested, finalResponseJson, usageJson,
                errorCode, errorMessage);

        WorkflowRepository.RunRow row = repository.find(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow 不存在"));
        WorkflowStatus current = status(row.status());
        if (requested==WorkflowStatus.SUCCEEDED && "/api/research/agents".equals(row.endpoint())
                && !repository.sealedAgentPublication(runId,answer,citations)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Agent 答案必须与服务端已核查的发布内容一致");
        }
        if (current.terminal()) {
            if (sameFinalization(row, requested, claimToken, finalizeFingerprint)) {
                return new FinalizeResponse(runId, current.name(), true);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "workflow 已处于其他终态或 finalize 请求不一致");
        }
        int changed = repository.finalizeClaim(runId, claimToken, requested,
                finalResponseJson, usageJson, errorCode, errorMessage, finalizeFingerprint);
        if (changed != 1) {
            WorkflowRepository.RunRow raced = repository.find(runId).orElse(null);
            if (raced != null && sameFinalization(raced, requested, claimToken, finalizeFingerprint)) {
                return new FinalizeResponse(runId, requested.name(), true);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "stale claim 或 workflow 已终止");
        }
        if (!answer.isBlank() && Set.of(WorkflowStatus.SUCCEEDED,
                WorkflowStatus.INSUFFICIENT_EVIDENCE).contains(requested)) {
            repository.insertFinalMessages(runId, row.sessionId(), row.userId(), row.question(), answer,
                    true);
        }
        repository.revokeGrantForRun(runId);
        Map<String, Object> terminalPayload = new LinkedHashMap<>();
        terminalPayload.put("status", requested.name());
        terminalPayload.put("hasAnswer", !answer.isBlank());
        if (errorCode != null && !errorCode.isBlank()) {
            terminalPayload.put("errorCode", errorCode);
        }
        repository.insertEvent(runId, "workflow:terminal:" + requested.name().toLowerCase(Locale.ROOT),
                "SYSTEM", null, requested.name(),
                writeJson(terminalPayload));
        return new FinalizeResponse(runId, requested.name(), false);
    }

    private boolean validCitationContract(String answer, List<String> citations) {
        if (citations.stream().anyMatch(String::isBlank)
                || citations.stream().distinct().count() != citations.size()) {
            return false;
        }
        Matcher matcher = CITATION_MARKER.matcher(answer);
        List<Integer> firstAppearance = new ArrayList<>();
        while (matcher.find()) {
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return false;
            }
            if (index < 1 || index > citations.size()) {
                return false;
            }
            if (!firstAppearance.contains(index)) {
                firstAppearance.add(index);
            }
        }
        if (firstAppearance.size() != citations.size()) {
            return false;
        }
        for (int index = 0; index < firstAppearance.size(); index++) {
            if (firstAppearance.get(index) != index + 1) {
                return false;
            }
        }
        return true;
    }

    private boolean sameFinalization(WorkflowRepository.RunRow row, WorkflowStatus requested,
                                     UUID claimToken, String fingerprint) {
        return status(row.status()) == requested
                && claimToken.equals(row.finalizedClaimToken())
                && fingerprint.equals(row.finalizeFingerprint());
    }

    private String finalizeFingerprint(WorkflowStatus status, String finalResponseJson, String usageJson,
                                       String errorCode, String errorMessage) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("status", status.name());
        canonical.put("finalResponse", json(finalResponseJson));
        canonical.put("usage", json(usageJson));
        canonical.put("errorCode", errorCode == null ? "" : errorCode);
        canonical.put("errorMessage", errorMessage == null ? "" : errorMessage);
        return ToolArgumentFingerprint.sha256(writeJson(canonical));
    }

    private Accepted replay(WorkflowRepository.RunRow row, String fingerprint) {
        if (!row.requestFingerprint().equals(fingerprint)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "同一 Idempotency-Key 不能对应不同请求体");
        }
        return accepted(row.runId(), row.sessionId(), row.status(), row.stage(), true);
    }

    private Accepted accepted(String runId, String sessionId, String status, String stage, boolean replayed) {
        String base = "/api/research/workflows/" + runId;
        return new Accepted(runId, sessionId, status, stage, base, base + "/events", replayed);
    }

    private View view(WorkflowRepository.RunRow row) {
        return new View(row.runId(), row.sessionId(), row.status(), row.stage(), progress(row.stage()),
                row.requestedScopes(), repository.eventsAfter(row.runId(), 0, 50),
                json(row.usageJson()), json(row.finalResponseJson()), row.errorCode(), row.errorMessage(),
                row.createdAt(), row.updatedAt(), repository.difyStopState(row.runId()).orElse(null));
    }

    private WorkflowRepository.RunRow owned(String runId, String userId) {
        var row=repository.findOwned(runId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow 不存在"));
        var identity=repository.agentIdentity(runId);
        if (identity.isPresent()) {
            var principal=userContextService.currentPrincipalRequired();
            if (!identity.get().tenantId().equals(principal.tenantId()) || !identity.get().ownerId().equals(principal.userId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"workflow 不存在");
            }
        }
        return row;
    }

    private List<String> normalizeScopes(List<String> requested) {
        List<String> values = requested == null || requested.isEmpty()
                ? List.copyOf(WorkflowAccessService.WORKFLOW_TOOLS) : requested;
        List<String> normalized = new ArrayList<>();
        for (String raw : values) {
            String scope = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!WorkflowAccessService.WORKFLOW_TOOLS.contains(scope)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "durable workflow 只允许 kb_search、web_search、calculator");
            }
            if (!normalized.contains(scope)) {
                normalized.add(scope);
            }
        }
        normalized.sort(String::compareTo);
        return List.copyOf(normalized);
    }

    private String fingerprint(CreateRequest request, List<String> scopes) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("question", request.question().trim());
        canonical.put("sessionId", request.sessionId() == null ? "" : request.sessionId().trim());
        canonical.put("requestedTools", scopes);
        return ToolArgumentFingerprint.sha256(writeJson(canonical));
    }

    private String boundedContext(AgentStateService.AgentContext context) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sessionSummary", truncate(context.sessionSummary(), 4000));
        snapshot.put("recentConversation", context.recentConversation().stream()
                .map(value -> truncate(value, 2000)).toList());
        snapshot.put("memories", context.memories().stream().map(value -> truncate(value, 2000)).toList());
        snapshot.put("diagnostics", context.diagnostics());
        String json = writeJson(snapshot);
        if (json.length() <= 24_000) {
            return json;
        }
        List<String> recent = new ArrayList<>(context.recentConversation().stream()
                .skip(Math.max(0, context.recentConversation().size() - 4))
                .map(value -> truncate(value, 1200)).toList());
        List<String> memories = new ArrayList<>(context.memories().stream().limit(3)
                .map(value -> truncate(value, 1200)).toList());
        String summary = truncate(context.sessionSummary(), 4000);
        snapshot.put("recentConversation", recent);
        snapshot.put("memories", memories);
        snapshot.put("truncated", true);
        json = writeJson(snapshot);
        while (json.length() > 24_000) {
            // Bound the serialized payload, including escaping, while prioritizing
            // the newest message and preserving complete JSON and Unicode code points.
            if (!memories.isEmpty()) memories.remove(memories.size() - 1);
            else if (recent.size() > 1) recent.remove(0);
            else if (!summary.isEmpty()) {
                summary = DifyContextInputs.truncate(summary, summary.codePointCount(0, summary.length()) / 2);
                snapshot.put("sessionSummary", summary);
            } else if (!recent.isEmpty()) {
                String newest = recent.get(0);
                if (newest.isEmpty()) recent.remove(0);
                else recent.set(0, DifyContextInputs.truncate(newest, newest.codePointCount(0, newest.length()) / 2));
            } else throw new IllegalStateException("context metadata exceeds snapshot limit");
            json = writeJson(snapshot);
        }
        return json;
    }

    private int progress(String stage) {
        return switch (status(stage)) {
            case QUEUED -> 0;
            case PLANNING -> 10;
            case WORKING -> 40;
            case DIFY_DISPATCHING -> 5;
            case DIFY_WORKING -> 40;
            case DISPATCH_UNKNOWN -> 5;
            case REVIEWING -> 65;
            case SYNTHESIZING -> 80;
            case FINALIZING -> 95;
            default -> 100;
        };
    }

    private WorkflowStatus status(String raw) {
        try {
            return WorkflowStatus.valueOf(raw);
        } catch (Exception failure) {
            throw new IllegalStateException("未知 workflow 状态：" + raw, failure);
        }
    }

    private WorkflowStatus terminalStatus(String raw) {
        WorkflowStatus parsed = status(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        if (!parsed.terminal()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "finalize 只能写入终态");
        }
        return parsed;
    }

    private String validateKey(String raw) {
        String key = raw == null ? "" : raw.trim();
        if (!key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key 需为 8-128 位安全字符");
        }
        return key;
    }

    private String safeError(String value, int limit) {
        return value == null ? null : truncate(value.replaceAll("[\\r\\n]+", " "), limit);
    }

    private String truncate(String value, int limit) {
        String normalized = value == null ? "" : value.trim();
        return normalized.codePointCount(0, normalized.length()) <= limit ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, limit)) + "…";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("workflow JSON 无法写入", failure);
        }
    }

    private JsonNode json(String value) {
        try {
            return value == null ? objectMapper.createObjectNode() : objectMapper.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException("workflow JSON 无法读取", failure);
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "durable workflow 尚未启用");
        }
    }
}
