package com.deepresearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDatasetLoaderTest {

    @TempDir
    Path datasetDirectory;

    @Test
    void loadsAdvancedPromptInjectionAndTenantSecurityCases() {
        var cases = new AgentDatasetLoader(new ObjectMapper()).load("agent-regression");

        assertThat(cases).hasSizeGreaterThanOrEqualTo(8);
        assertThat(cases).anySatisfy(testCase -> {
            assertThat(testCase.id()).isEqualTo("agent-sec-001");
            assertThat(testCase.promptInjectionCase()).isTrue();
            assertThat(testCase.securityCase()).isTrue();
            assertThat(testCase.forbiddenTools()).contains("file_read");
        });
        assertThat(cases).anySatisfy(testCase -> {
            assertThat(testCase.id()).isEqualTo("agent-sec-002");
            assertThat(testCase.mustNotContain()).contains("tenant-b-secret");
        });
    }

    @Test
    void loadsProjectBoundaryCasesWithContentBasedSafeTerminalContract() {
        var cases = new AgentDatasetLoader(new ObjectMapper()).load("project-knowledge-gold");

        assertThat(cases).hasSize(29);
        assertThat(cases).filteredOn(testCase -> testCase.id().startsWith("project-kb-neg-"))
                .hasSize(4)
                .allSatisfy(testCase -> {
                    assertThat(testCase.mustContainAny()).isNotEmpty();
                    assertThat(testCase.mustNotContain()).isNotEmpty();
                    assertThat(testCase.expectedCitationMarkers()).isNullOrEmpty();
                    assertThat(testCase.expectedFacts()).isNullOrEmpty();
                });
        assertThat(cases).filteredOn(testCase -> testCase.id().startsWith("project-kb-neg-"))
                .allSatisfy(testCase -> {
                    assertThat(testCase.expectedStatus()).isNull();
                    assertThat(testCase.expectedWorkflowStatus()).isNull();
                });
        assertThat(cases).filteredOn(testCase -> testCase.id().equals("project-kb-neg-002"))
                .singleElement()
                .satisfies(testCase -> {
                    assertThat(testCase.requireRefusalWhenNoEvidence()).isTrue();
                    assertThat(testCase.mustNotContain()).contains("eyJ", "secret=")
                            .doesNotContain("Bearer ");
                });
    }

    @Test
    void keepsDefaultAndTrimmedDatasetNameCompatibility() throws Exception {
        writeDataset("agent-demo", "{\"id\":\"case-1\",\"question\":\"question\"}");
        AgentDatasetLoader loader = loader();

        assertThat(loader.normalizeName(null)).isEqualTo("agent-demo");
        assertThat(loader.normalizeName("   ")).isEqualTo("agent-demo");
        assertThat(loader.normalizeName(" agent-demo ")).isEqualTo("agent-demo");
        assertThat(loader.load(null)).singleElement()
                .satisfies(testCase -> assertThat(testCase.id()).isEqualTo("case-1"));
    }

    @Test
    void rejectsUnsafeOrNonCanonicalDatasetNames() {
        AgentDatasetLoader loader = loader();

        for (String name : new String[]{"../secret", "Agent-Demo", "_agent", "agent.demo", "agent/demo"}) {
            assertThatIllegalArgumentException()
                    .as("dataset name %s", name)
                    .isThrownBy(() -> loader.load(name))
                    .withMessageContaining("数据集名称只能包含");
        }
    }

    @Test
    void reportsMalformedJsonLineNumber() throws Exception {
        writeDataset("malformed",
                "# comment",
                "{\"id\":\"case-1\",\"question\":\"question\"}",
                "{not-json}");

        assertThatThrownBy(() -> loader().load("malformed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed.jsonl")
                .hasMessageContaining("第 3 行");
    }

    @Test
    void rejectsEmptyDataset() throws Exception {
        writeDataset("empty", "", "  # comment", "   ");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load("empty"))
                .withMessageContaining("数据集不能为空");
    }

    @Test
    void rejectsEmptyAndDuplicateCaseIdsWithLineNumber() throws Exception {
        writeDataset("empty-id", "{\"id\":\"  \",\"question\":\"question\"}");
        writeDataset("duplicate-id",
                "{\"id\":\"same\",\"question\":\"first\"}",
                "{\"id\":\" same \",\"question\":\"second\"}");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load("empty-id"))
                .withMessageContaining("第 1 行")
                .withMessageContaining("id 不能为空");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load("duplicate-id"))
                .withMessageContaining("第 2 行")
                .withMessageContaining("id 重复：same");
    }

    @Test
    void rejectsBlankQuestionAndConflictingTools() throws Exception {
        writeDataset("blank-question", "{\"id\":\"case-1\",\"question\":\"  \"}");
        writeDataset("tool-conflict", """
                {"id":"case-1","question":"question","expectedTools":["KB_SEARCH"],"forbiddenTools":["knowledge_search"]}
                """);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load("blank-question"))
                .withMessageContaining("第 1 行")
                .withMessageContaining("question 不能为空");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader().load("tool-conflict"))
                .withMessageContaining("第 1 行")
                .withMessageContaining("expectedTools 与 forbiddenTools 冲突")
                .withMessageContaining("knowledge_search");
    }

    private AgentDatasetLoader loader() {
        return new AgentDatasetLoader(new ObjectMapper(), datasetDirectory);
    }

    private void writeDataset(String name, String... lines) throws Exception {
        Files.writeString(datasetDirectory.resolve(name + ".jsonl"), String.join(System.lineSeparator(), lines));
    }
}
