package com.deepresearch.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FileReadToolTest {

    @TempDir
    Path projectRoot;

    @Test
    void readsAllowedFileAndRedactsCredentials() throws Exception {
        Path allowed = Files.createDirectories(projectRoot.resolve("allowed"));
        Files.writeString(allowed.resolve("notes.txt"), "hello\napi_key=top-secret-value\nBearer abc.def.ghi");

        String result = tool().execute("allowed/notes.txt");

        assertThat(result)
                .contains("hello", "api_key=[REDACTED]", "Bearer [REDACTED]")
                .doesNotContain("top-secret-value", "abc.def.ghi");
    }

    @Test
    void rejectsBlankTraversalAbsoluteAndOutsideAllowlistPaths() throws Exception {
        Files.createDirectories(projectRoot.resolve("allowed"));
        Files.writeString(projectRoot.resolve("outside.txt"), "outside");
        FileReadTool tool = tool();

        assertThat(tool.execute(" ")).contains("路径为空");
        assertThat(tool.execute("allowed/../../outside.txt")).contains("被拒绝");
        assertThat(tool.execute(projectRoot.resolve("allowed/file.txt").toString())).contains("只允许项目内相对路径");
        assertThat(tool.execute("outside.txt")).contains("被拒绝");
    }

    @Test
    void rejectsSymbolicLinkThatEscapesAfterRealPathResolution() throws Exception {
        Path allowed = Files.createDirectories(projectRoot.resolve("allowed"));
        Path outside = Files.writeString(projectRoot.resolve("outside.txt"), "private");
        Files.createSymbolicLink(allowed.resolve("escape.txt"), outside);

        String result = tool().execute("allowed/escape.txt");

        assertThat(result).contains("真实路径不在允许范围内").doesNotContain("private");
    }

    @Test
    void truncatesLargeFileAndLimitsDirectoryListing() throws Exception {
        Path allowed = Files.createDirectories(projectRoot.resolve("allowed"));
        Files.writeString(allowed.resolve("large.txt"), "x".repeat(700));
        for (int i : IntStream.range(0, 85).toArray()) {
            Files.writeString(allowed.resolve("entry-%02d.txt".formatted(i)), "x");
        }
        FileReadTool tool = tool();

        assertThat(tool.execute("allowed/large.txt")).contains("已截断");
        assertThat(tool.execute("allowed")).contains("目录结果已限制为 80 项");
    }

    private FileReadTool tool() {
        return new FileReadTool(List.of("allowed"), 500, projectRoot);
    }
}
