package com.deepresearch.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpStatus.FORBIDDEN;

class AgentContextServiceTest {
    @Test
    void foreignSessionIsRejectedBeforeReadingAnyMessagesOrMemories() {
        var sessions = mock(AgentSessionRepository.class);
        var memories = mock(MemorySelectionService.class);
        var context = new AgentContextService(sessions, memories, 8);
        doThrow(new ResponseStatusException(FORBIDDEN, "not owned"))
                .when(sessions).upsertOwned(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> context.prepare("foreign-session", "tenant-a:user-a", "continue"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(memories);
    }
}
