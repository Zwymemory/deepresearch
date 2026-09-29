package com.deepresearch.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationSummaryServiceTest {
    @Test
    void disabledSummaryCannotClaimTheDeltaWasSummarized() {
        var client = mock(ChatClient.class);
        assertThat(new ConversationSummaryService(client, false, 1200).summarize("old", List.of("delta"))).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void providerFailureOrEmptyOutputCannotClaimSuccessfulSummary() {
        var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        var service = new ConversationSummaryService(client, true, 1200);
        when(client.prompt().user(anyString()).options(any()).call().content())
                .thenThrow(new IllegalStateException("synthetic error")).thenReturn("  ");
        assertThat(service.summarize("old", List.of("delta"))).isNull();
        assertThat(service.summarize("old", List.of("delta"))).isNull();
    }
}
