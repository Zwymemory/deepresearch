package com.deepresearch.service;

import com.deepresearch.web.dto.AgentMemoryResponse;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemorySelectionServiceTest {
    private final AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
    private final MemorySelectionService service = new MemorySelectionService(repository, 5);

    @Test
    void selectsOnlyOwnedRowsAndDoesNotClaimModelUse() {
        when(repository.list("tenant-a:user-a", 100)).thenReturn(List.of(
                memory("own", "tenant-a:user-a"), memory("foreign", "tenant-b:user-a")));
        var selected = service.select("tenant-a:user-a", "研究方法");
        assertThat(selected.rows()).hasSize(1);
        assertThat(selected.totalCount()).isEqualTo(1);
        assertThat(selected.rows().get(0)).contains("研究方法");
        verify(repository).list("tenant-a:user-a", 100);
        verify(repository, never()).markUsed(anyString());
    }

    @Test
    void noOwnedMemoryIsEmptyAndDoesNotBorrowAnotherUsersRow() {
        when(repository.list("tenant-a:user-a", 100)).thenReturn(List.of(memory("foreign", "tenant-b:user-a")));
        assertThat(service.select("tenant-a:user-a", "研究方法").rows()).isEmpty();
        verify(repository, never()).markUsed(anyString());
    }

    private AgentMemoryResponse memory(String id, String user) {
        return new AgentMemoryResponse(id, user, "progress", "研究方法待办", "manual", 0.7,
                OffsetDateTime.parse("2026-09-29T00:00:00Z"), OffsetDateTime.parse("2026-09-29T00:00:00Z"));
    }
}
