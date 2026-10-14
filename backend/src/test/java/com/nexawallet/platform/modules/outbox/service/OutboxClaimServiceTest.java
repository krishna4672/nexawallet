package com.nexawallet.platform.modules.outbox.service;

import com.nexawallet.platform.modules.outbox.entity.OutboxEventEntity;
import com.nexawallet.platform.modules.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxClaimService — Unit Tests")
class OutboxClaimServiceTest {

    @Mock private OutboxEventRepository outboxEventRepository;

    private OutboxClaimService claimService() {
        return new OutboxClaimService(outboxEventRepository);
    }

    @Test
    @DisplayName("1. No eligible rows — short-circuits without a bulk update or lookup")
    void claimBatch_noEligibleRows_doesNotUpdate() {
        when(outboxEventRepository.selectClaimableIdsForUpdateSkipLocked(any(), anyInt())).thenReturn(List.of());

        List<OutboxEventEntity> result = claimService().claimBatch(50);

        assertThat(result).isEmpty();
        verify(outboxEventRepository, never()).markPublishing(any());
        verify(outboxEventRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("2. Eligible rows are flipped to PUBLISHING then re-fetched")
    void claimBatch_withEligibleRows_marksPublishingAndFetches() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<UUID> ids = List.of(id1, id2);
        when(outboxEventRepository.selectClaimableIdsForUpdateSkipLocked(any(), anyInt())).thenReturn(ids);
        when(outboxEventRepository.findAllById(ids)).thenReturn(
                List.of(OutboxEventEntity.builder().id(id1).build(), OutboxEventEntity.builder().id(id2).build()));

        List<OutboxEventEntity> result = claimService().claimBatch(50);

        assertThat(result).hasSize(2);
        verify(outboxEventRepository).markPublishing(ids);
        verify(outboxEventRepository).findAllById(ids);
    }
}
