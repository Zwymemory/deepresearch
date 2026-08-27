package com.deepresearch.agent;

import java.util.regex.Pattern;

/**
 * 工具输出的最后一道敏感信息保护。
 *
 * 输入是外部系统或本地文件返回的非可信文本，输出是可安全进入 Agent 上下文的文本。
 * 这里只处理通用凭据形态；权限校验仍由各工具在读取数据前完成。
 */
final class ToolOutputSanitizer {

    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|password|passwd|secret|token)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(https?://)[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern SOURCE_MARKER = Pattern.compile("(?i)\\[(?:来源|source)\\s*\\d+]");

    private ToolOutputSanitizer() {
    }

    static String redactSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String redacted = NAMED_SECRET.matcher(text).replaceAll("$1$2[REDACTED]");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer [REDACTED]");
        return URL_CREDENTIALS.matcher(redacted).replaceAll("$1[REDACTED]@");
    }

    static String markUntrusted(String source, String text) {
        String safeSource = source == null ? "external" : source.replaceAll("[^A-Za-z0-9_-]", "");
        return "[UNTRUSTED_DATA_BEGIN source=" + safeSource + "]\n"
                + redactSecrets(text)
                + "\n[UNTRUSTED_DATA_END source=" + safeSource + "]";
    }

    /** Prevents an untrusted title or snippet from minting a model-visible citation number. */
    static String neutralizeCitationMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return SOURCE_MARKER.matcher(text).replaceAll("［未验证来源］");
    }
}
