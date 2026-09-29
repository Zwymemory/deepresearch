package com.deepresearch.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationCompressionCoordinatorTest {
    private final AgentSessionRepository repository = mock(AgentSessionRepository.class);
    private final ConversationSummaryService summary = mock(ConversationSummaryService.class);
    private final ConversationCompressionCoordinator coordinator = new ConversationCompressionCoordinator(repository, summary, 8, 12);

    @Test
    void failedGenerationPreservesSummaryWatermarkSoDeltaCanBeRetried() {
        pendingDelta();
        when(summary.summarize("old summary", List.of("old delta"))).thenReturn(null);
        coordinator.compressIfNeeded("session");
        verify(repository, never()).updateSummary(anyString(), anyString(), anyInt());
    }

    @Test
    void blankSummaryDoesNotEraseOldTextOrAdvanceProgress() {
        pendingDelta();
        when(summary.summarize("old summary", List.of("old delta"))).thenReturn(" ");
        coordinator.compressIfNeeded("session");
        verify(repository, never()).updateSummary(anyString(), anyString(), anyInt());
    }

    @Test
    void successfulDeltaCommitsOnlyItsCompletedWatermark() {
        pendingDelta();
        when(summary.summarize("old summary", List.of("old delta"))).thenReturn("updated summary");
        coordinator.compressIfNeeded("session");
        verify(repository).updateSummary("session", "updated summary", 6);
    }

    private void pendingDelta() {
        when(repository.messageCount("session")).thenReturn(14);
        when(repository.summaryMessageCount("session")).thenReturn(4);
        when(repository.messagesForSummary("session", 4, 2)).thenReturn(List.of("old delta"));
        when(repository.summary("session")).thenReturn("old summary");
    }
}
