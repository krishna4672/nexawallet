package com.nexawallet.platform.modules.discovery.service;

import com.nexawallet.platform.common.exception.ResourceNotFoundException;
import com.nexawallet.platform.modules.discovery.dto.ServicePointCreateRequest;
import com.nexawallet.platform.modules.discovery.dto.ServicePointUpdateRequest;
import com.nexawallet.platform.modules.discovery.entity.ServicePointEntity;
import com.nexawallet.platform.modules.discovery.entity.ServicePointType;
import com.nexawallet.platform.modules.discovery.repository.ServicePointRepository;
import com.nexawallet.platform.modules.outbox.service.OutboxEventWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServicePointAdminService — Unit Tests")
class ServicePointAdminServiceTest {

    @Mock private ServicePointRepository servicePointRepository;
    @Mock private OutboxEventWriter outboxEventWriter;

    private ServicePointAdminService service;
    private UUID id;

    @BeforeEach
    void setUp() {
        service = new ServicePointAdminService(servicePointRepository, outboxEventWriter);
        id = UUID.randomUUID();
    }

    private ServicePointEntity existingEntity() {
        return ServicePointEntity.builder()
                .id(id)
                .name("Downtown ATM")
                .type(ServicePointType.ATM)
                .latitude(40.0)
                .longitude(-73.0)
                .geohash("dr5reg")
                .address("123 Main St")
                .active(true)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    @Test
    @DisplayName("1. create() persists the entity and records exactly one SERVICE_POINT_CREATED outbox event")
    void create_success_recordsOutboxEvent() {
        ServicePointCreateRequest request = ServicePointCreateRequest.builder()
                .name("Downtown ATM").type(ServicePointType.ATM).latitude(40.0).longitude(-73.0).address("123 Main St")
                .build();
        when(servicePointRepository.save(any())).thenAnswer(inv -> {
            ServicePointEntity e = inv.getArgument(0);
            e.setId(id);
            e.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            e.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            return e;
        });

        service.create(request);

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxEventWriter).record(eq("ServicePoint"), eq(id), eq("SERVICE_POINT_CREATED"), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("name", "Downtown ATM").containsEntry("active", true);
    }

    @Test
    @DisplayName("2. create() computes and persists a geohash for the given coordinates")
    void create_computesGeohash() {
        ServicePointCreateRequest request = ServicePointCreateRequest.builder()
                .name("X").type(ServicePointType.BRANCH).latitude(40.0).longitude(-73.0)
                .build();
        when(servicePointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(request);

        assertThat(response.getGeohash()).isNotBlank();
        assertThat(com.nexawallet.platform.common.geo.GeohashUtils.decodeBoundingBox(response.getGeohash())[0])
                .isLessThanOrEqualTo(40.0);
    }

    @Test
    @DisplayName("3. If the repository save fails, no outbox event is ever recorded")
    void create_repositoryFails_noOutboxEvent() {
        ServicePointCreateRequest request = ServicePointCreateRequest.builder()
                .name("X").type(ServicePointType.MERCHANT).latitude(10.0).longitude(10.0)
                .build();
        when(servicePointRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(RuntimeException.class);

        verify(outboxEventWriter, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("4. update() with active=false records a SERVICE_POINT_DEACTIVATED event (not UPDATED)")
    void update_toInactive_recordsDeactivatedEvent() {
        ServicePointEntity existing = existingEntity();
        when(servicePointRepository.findById(id)).thenReturn(Optional.of(existing));
        when(servicePointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(id, ServicePointUpdateRequest.builder().active(false).build());

        verify(outboxEventWriter).record(eq("ServicePoint"), eq(id), eq("SERVICE_POINT_DEACTIVATED"), any());
    }

    @Test
    @DisplayName("5. update() changing coordinates recomputes the geohash")
    void update_changingCoordinates_recomputesGeohash() {
        ServicePointEntity existing = existingEntity();
        String originalGeohash = existing.getGeohash();
        when(servicePointRepository.findById(id)).thenReturn(Optional.of(existing));
        when(servicePointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.update(id, ServicePointUpdateRequest.builder().latitude(-33.8688).longitude(151.2093).build());

        assertThat(response.getGeohash()).isNotEqualTo(originalGeohash);
    }

    @Test
    @DisplayName("6. update() on a non-existent id throws ResourceNotFoundException and records no outbox event")
    void update_nonExistentId_throws() {
        when(servicePointRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, ServicePointUpdateRequest.builder().name("x").build()))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(outboxEventWriter, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("7. deactivate() sets active=false and records SERVICE_POINT_DEACTIVATED")
    void deactivate_success() {
        ServicePointEntity existing = existingEntity();
        when(servicePointRepository.findById(id)).thenReturn(Optional.of(existing));
        when(servicePointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.deactivate(id);

        assertThat(response.isActive()).isFalse();
        verify(outboxEventWriter).record(eq("ServicePoint"), eq(id), eq("SERVICE_POINT_DEACTIVATED"), any());
    }
}
