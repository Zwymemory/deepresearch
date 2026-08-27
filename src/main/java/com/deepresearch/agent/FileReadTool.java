package com.deepresearch.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * W10：受限本地文件读取工具。
 *
 * 第一版不直接拉起外部 MCP server，而是先把文件能力按 Tool 协议封装出来：
 * - 有工具名和描述
 * - 有 allowlist
 * - 有最大读取长度
 * - 返回 Observation
 *
 * 这不是 MCP 协议实现；后续真实 MCP filesystem 集成仍复用这里的安全边界。
 */
@Component
public class FileReadTool implements Tool {

    private final Path projectRoot;
    private final List<Path> allowedRoots;
    private final int maxChars;

    @Autowired
    public FileReadTool(@Value("${deepresearch.file-tool.allowed-roots:testdata,src/main/resources/static,README.md,W8-W10开发路线.md}") List<String> allowedRoots,
                        @Value("${deepresearch.file-tool.max-chars:6000}") int maxChars) {
        this(allowedRoots, maxChars, Path.of(""));
    }

    FileReadTool(List<String> allowedRoots, int maxChars, Path projectRoot) {
        this.projectRoot = canonicalize(projectRoot.toAbsolutePath().normalize());
        this.allowedRoots = allowedRoots.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(root -> canonicalize(this.projectRoot.resolve(root).normalize()))
                .toList();
        this.maxChars = Math.max(500, maxChars);
    }

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public String description() {
        return "读取项目内允许目录下的文本文件。输入相对路径，如 README.md、testdata/eval/agent-harness.jsonl。"
                + "适合读取本地报告、评测集、README、demo 文档。不能读取 allowlist 外的文件。";
    }

    @Override
    public String execute(String input) {
        if (input == null || input.isBlank()) {
            return "（文件读取失败：路径为空）";
        }
        String rawPath = firstLine(input).replace("\"", "").replace("'", "").trim();
        Path requested = Path.of(rawPath);
        if (requested.isAbsolute()) {
            return "（文件读取被拒绝：只允许项目内相对路径）";
        }
        Path target = projectRoot.resolve(requested).normalize();
        if (!isAllowed(target)) {
            return "（文件读取被拒绝：路径不在允许范围内：" + rawPath + "）";
        }
        if (!Files.exists(target)) {
            return "（文件读取失败：文件不存在：" + rawPath + "）";
        }
        try {
            Path realTarget = target.toRealPath();
            if (!isAllowed(realTarget)) {
                return "（文件读取被拒绝：真实路径不在允许范围内）";
            }
            if (Files.isDirectory(realTarget)) {
                return readDirectory(realTarget, rawPath);
            }
            if (!Files.isRegularFile(realTarget)) {
                return "（文件读取失败：不是普通文件：" + rawPath + "）";
            }
            String content = ToolOutputSanitizer.redactSecrets(
                    Files.readString(realTarget, StandardCharsets.UTF_8));
            boolean truncated = content.length() > maxChars;
            String preview = truncated ? content.substring(0, maxChars) + "\n...（已截断）" : content;
            return ToolOutputSanitizer.markUntrusted("file", "文件: " + projectRoot.relativize(realTarget) + "\n"
                    + "字符数: " + content.length() + "\n"
                    + "内容:\n" + preview);
        } catch (IOException e) {
            return "（文件读取失败：无法安全读取目标）";
        }
    }

    private boolean isAllowed(Path target) {
        for (Path root : allowedRoots) {
            boolean fileRoot = Files.exists(root) && !Files.isDirectory(root);
            if (target.equals(root) || (!fileRoot && target.startsWith(root))) {
                return true;
            }
        }
        return false;
    }

    private String readDirectory(Path target, String rawPath) {
        try (var stream = Files.list(target)) {
            List<Path> paths = stream.limit(81).toList();
            List<String> names = new ArrayList<>();
            for (Path path : paths.subList(0, Math.min(80, paths.size()))) {
                names.add(Files.isDirectory(path) ? path.getFileName() + "/" : path.getFileName().toString());
            }
            names.sort(String::compareTo);
            String rows = names.stream().map(item -> "- " + item).reduce("", (a, b) -> a + b + "\n").trim();
            if (paths.size() > 80) {
                rows += "\n...（目录结果已限制为 80 项）";
            }
            return "目录: " + rawPath + "\n" + (rows.isBlank() ? "（空目录）" : rows);
        } catch (IOException e) {
            return "（目录读取失败：无法安全列出目标）";
        }
    }

    private Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private String firstLine(String text) {
        int lineBreak = text.indexOf('\n');
        return lineBreak < 0 ? text : text.substring(0, lineBreak);
    }
}
