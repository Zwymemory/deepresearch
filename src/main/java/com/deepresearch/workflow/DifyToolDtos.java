package com.deepresearch.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request and safe response used only by the dedicated Dify service identity. */
public final class DifyToolDtos {
    private DifyToolDtos() {}

    public record Request(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{1,63}") String runId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{7,159}") String callId,
            @NotBlank @Size(max = 1000) String input) {}

    public record Evidence(String citationId, String sourceId, String title,
                           String content, boolean untrusted, String url) {
        public Evidence(String citationId, String sourceId, String title,
                        String content, boolean untrusted) {
            this(citationId, sourceId, title, content, untrusted, "");
        }
    }

    public record Response(boolean success, String code, String tool,
                           List<Evidence> evidences, String value) {
        public static Response failure(String tool, String code) {
            return new Response(false, code, tool, List.of(), "");
        }
    }
}
