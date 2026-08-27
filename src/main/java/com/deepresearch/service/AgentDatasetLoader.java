package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 从受控 testdata/eval 目录加载 JSONL Agent 回归集；解析失败时终止评测而不跳过坏行。 */
@Service
class AgentDatasetLoader {

    static final String DEFAULT_DATASET = "agent-demo";
    private static final Pattern DATASET_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private final ObjectMapper objectMapper;
    private final Path datasetDirectory;

    @Autowired
    AgentDatasetLoader(ObjectMapper objectMapper) {
        this(objectMapper, Path.of("testdata/eval"));
    }

    AgentDatasetLoader(ObjectMapper objectMapper, Path datasetDirectory) {
        this.objectMapper = objectMapper;
        this.datasetDirectory = datasetDirectory.toAbsolutePath().normalize();
    }

    String normalizeName(String dataset) {
        String normalized = dataset == null || dataset.isBlank() ? DEFAULT_DATASET : dataset.trim();
        if (!DATASET_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Agent Harness 数据集名称只能包含小写字母、数字、-、_，且首位必须是字母或数字："
                            + normalized);
        }
        return normalized;
    }

    List<AgentHarnessRequest.AgentHarnessCase> load(String dataset) {
        String normalizedDataset = normalizeName(dataset);
        Path path = datasetDirectory.resolve(normalizedDataset + ".jsonl").normalize();
        if (!path.startsWith(datasetDirectory)) {
            throw new IllegalArgumentException("Agent Harness 数据集路径越出受控目录：" + path);
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Agent Harness 数据集不存在：" + path);
        }
        List<AgentHarnessRequest.AgentHarnessCase> cases = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.trim().startsWith("#")) {
                    continue;
                }
                AgentHarnessRequest.AgentHarnessCase testCase;
                try {
                    testCase = objectMapper.readValue(line, AgentHarnessRequest.AgentHarnessCase.class);
                } catch (IOException exception) {
                    throw new IllegalStateException(
                            "解析 Agent Harness 数据集失败：" + path + " 第 " + lineNumber + " 行",
                            exception);
                }
                validateCase(path, lineNumber, testCase, caseIds);
                cases.add(testCase);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Agent Harness 数据集失败：" + path, exception);
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Agent Harness 数据集不能为空：" + path);
        }
        return cases;
    }

    private void validateCase(Path path,
                              int lineNumber,
                              AgentHarnessRequest.AgentHarnessCase testCase,
                              Set<String> caseIds) {
        if (testCase == null) {
            throw invalidCase(path, lineNumber, "case 不能为 null");
        }
        String id = testCase.id() == null ? "" : testCase.id().trim();
        if (id.isEmpty()) {
            throw invalidCase(path, lineNumber, "id 不能为空");
        }
        if (!caseIds.add(id)) {
            throw invalidCase(path, lineNumber, "id 重复：" + id);
        }
        if (testCase.question() == null || testCase.question().isBlank()) {
            throw invalidCase(path, lineNumber, "question 不能为空");
        }
        if (testCase.maxRounds() != null && testCase.maxRounds() <= 0) {
            throw invalidCase(path, lineNumber, "maxRounds 必须大于 0");
        }
        for (String setup : testCase.sessionSetup() == null ? List.<String>of() : testCase.sessionSetup()) {
            if (setup == null || setup.isBlank()) {
                throw invalidCase(path, lineNumber, "sessionSetup 不能包含空问题");
            }
        }

        Set<String> expectedTools = normalizedTools(testCase.expectedTools());
        Set<String> forbiddenTools = normalizedTools(testCase.forbiddenTools());
        expectedTools.retainAll(forbiddenTools);
        if (!expectedTools.isEmpty()) {
            throw invalidCase(path, lineNumber,
                    "expectedTools 与 forbiddenTools 冲突：" + expectedTools);
        }
    }

    private Set<String> normalizedTools(List<String> tools) {
        Set<String> normalized = new HashSet<>();
        if (tools != null) {
            tools.stream()
                    .filter(tool -> tool != null && !tool.isBlank())
                    .map(this::normalizeTool)
                    .forEach(normalized::add);
        }
        return normalized;
    }

    private String normalizeTool(String tool) {
        return switch (tool.trim().toLowerCase(Locale.ROOT)) {
            case "kb_search", "searchknowledge", "knowledge_search" -> "knowledge_search";
            case "calculator", "calculate" -> "calculator";
            case "file_read", "readprojectfile" -> "file_read";
            case "web_search", "searchweb", "search" -> "web_search";
            default -> tool.trim().toLowerCase(Locale.ROOT);
        };
    }

    private IllegalArgumentException invalidCase(Path path, int lineNumber, String reason) {
        return new IllegalArgumentException(
                "Agent Harness 数据集 case 非法：" + path + " 第 " + lineNumber + " 行，" + reason);
    }
}
