package com.deepresearch.web;

import com.deepresearch.tool.TavilySearchClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchToolCapabilitiesControllerTest {
    @Test
    void exposesOnlyConfigurationSignalAndSafeExplanation() {
        TavilySearchClient search = mock(TavilySearchClient.class);
        var controller = new ResearchToolCapabilitiesController(search);
        assertThat(controller.capabilities().toString()).contains("WEB_SEARCH_NOT_CONFIGURED", "configured=false")
                .doesNotContain("api_key");
        when(search.configured()).thenReturn(true);
        assertThat(controller.capabilities().toString()).contains("configured=true", "实际调用仍可能失败")
                .doesNotContain("api_key");
    }
}
