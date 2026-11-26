package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.reservation.api.ReservationRef;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code ReservationService} 단위 테스트 — Repository는 가짜(DB 없음). 정비소 격리(404)와 호출 순서·인자를 본다.
 * 실제 저장·{@code @Version} 증가·동시 요청(R8·R10·R11)과 JPQL 정렬은 DB가 필요한데, 그 DB 테스트
 * ({@code ReservationServiceDbTest})는 아직 없다 — 로컬에 DB가 없어 이 PR에서는 미검증이다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    @Mock
    private ReservationRepository repository;

    @Mock
    private ReservationItemRepository itemRepository;

    @Mock
    private CustomerReader customerReader;

    @Mock
    private ItemReader itemReader;
    @Mock
    private StaffReader staffReader;

    private ReservationService service() {
        return new ReservationService(repository, itemRepository, customerReader, itemReader, staffReader, clock);
    }

    private static Reservation reservation(long id, long shopId, long vehicleId, LocalDateTime at,
                                           ReservationStatus status, int version) {
        Reservation r = Reservation.register(shopId, vehicleId, at, false, null, 7L);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", version);
        return r;
    }

    // ---- 방문 완료 (API 18) ----

    @Test
    @DisplayName("방문 완료: 소유 확인 → 전이 → 저장(flush), 응답은 08 18번 모양")
    void complete() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));

        ReservationResponse response = service().complete(3L, 1L, 0);

        assertThat(response.reservationId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(response.vehicleId()).isEqualTo(4L);
        verify(repository).saveAndFlush(r);
    }

    @Test
    @DisplayName("X 격리: 다른 정비소 예약 id → 404 NOT_FOUND, 저장 없음 (없는 id와 같은 응답)")
    void completeOtherShopsReservation() {
        when(repository.findByIdAndShopId(1L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().complete(9L, 1L, 0))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("R5 취소된 예약 → 409 INVALID_STATUS_TRANSITION, 저장 없음")
    void completeCanceled() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.CANCELED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service().complete(3L, 1L, 0))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("R12 낡은 version → 409 CONCURRENT_MODIFICATION, 저장 없음")
    void completeWithStaleVersion() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, 1);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service().complete(3L, 1L, 0))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
        verify(repository, never()).saveAndFlush(any());
    }

    // ---- ReservationReader.findActiveByVehicleIds ----

    @Test
    @DisplayName("차량 id가 비어 있으면 조회하지 않고 빈 맵")
    void activeWithNoVehicles() {
        assertThat(service().findActiveByVehicleIds(3L, Set.of())).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("유효 예약 기준: RESERVED + 오늘 0시 이후(Clock 기준, 오늘 지난 시각 포함) — 조회 인자 확인")
    void activeQueryUsesTodayStartAndReserved() {
        when(repository.findByVehiclesAndStatusFrom(3L, Set.of(4L), ReservationStatus.RESERVED,
            LocalDateTime.of(2026, 11, 24, 0, 0))).thenReturn(List.of());

        assertThat(service().findActiveByVehicleIds(3L, Set.of(4L))).isEmpty();

        verify(repository).findByVehiclesAndStatusFrom(3L, Set.of(4L), ReservationStatus.RESERVED,
            LocalDateTime.of(2026, 11, 24, 0, 0));
    }

    @Test
    @DisplayName("차량마다 가장 이른 유효 예약 하나, 예약 없는 차량은 키가 없다, 같은 차량이 둘이어도 예외 없음")
    void activePicksEarliestPerVehicle() {
        Reservation early = reservation(5, 3, 4, LocalDateTime.of(2026, 11, 26, 10, 0), ReservationStatus.RESERVED, 0);
        Reservation late = reservation(6, 3, 4, LocalDateTime.of(2026, 11, 28, 10, 0), ReservationStatus.RESERVED, 0);
        Reservation other = reservation(7, 3, 8, LocalDateTime.of(2026, 11, 24, 9, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByVehiclesAndStatusFrom(any(), any(), any(), any()))
            .thenReturn(List.of(other, early, late)); // 저장소가 이른 순서로 준다고 가정

        Map<Long, ReservationRef> active = service().findActiveByVehicleIds(3L, Set.of(4L, 8L, 12L));

        assertThat(active).containsOnlyKeys(4L, 8L);
        assertThat(active.get(4L)).isEqualTo(new ReservationRef(5L, 4L, LocalDateTime.of(2026, 11, 26, 10, 0)));
        assertThat(active.get(8L)).isEqualTo(new ReservationRef(7L, 8L, LocalDateTime.of(2026, 11, 24, 9, 0)));
    }
}
