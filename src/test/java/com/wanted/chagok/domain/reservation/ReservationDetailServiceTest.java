package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.reservation.api.ReservationDetail;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationItem;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRevisionRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 예약 상세 패널용 한 건 조회 {@code ReservationReader.getOwnedDetail} (08 17번, X3, D-23) — Repository는 가짜(DB 없음).
 * 소속 확인(없음·남의 정비소 = 같은 404)과 "처리 필요" 판정, 값 옮기기를 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationDetailServiceTest {

    private static final long SHOP = 3L;
    private static final long VEHICLE = 4L;
    private static final long RES_ID = 5L;

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    @Mock
    private ReservationRepository repository;

    @Mock
    private ReservationItemRepository itemRepository;

    @Mock
    private ReservationRevisionRepository revisionRepository;

    @Mock
    private CustomerReader customerReader;

    @Mock
    private ItemReader itemReader;
    @Mock
    private StaffReader staffReader;

    private ReservationService service() {
        return new ReservationService(repository, itemRepository, revisionRepository, customerReader, itemReader, staffReader, clock);
    }

    private Reservation stored(LocalDateTime at, ReservationStatus status, int version) {
        Reservation r = Reservation.register(SHOP, VEHICLE, at, true, "에어컨 소음", 7L);
        ReflectionTestUtils.setField(r, "id", RES_ID);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", version);
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("값을 그대로 옮긴다: 일시·상태·version·목적(항목 id·기타·메모). 시각이 지난 RESERVED는 needsAction")
    void mapsValues() {
        stored(LocalDateTime.of(2026, 11, 23, 15, 0), ReservationStatus.RESERVED, 2);
        when(itemRepository.findByShopIdAndReservationIdIn(SHOP, List.of(RES_ID)))
            .thenReturn(List.of(ReservationItem.of(SHOP, RES_ID, 6L), ReservationItem.of(SHOP, RES_ID, 1L)));

        ReservationDetail detail = service().getOwnedDetail(SHOP, RES_ID);

        assertThat(detail.reservationId()).isEqualTo(RES_ID);
        assertThat(detail.vehicleId()).isEqualTo(VEHICLE);
        assertThat(detail.reservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 23, 15, 0));
        assertThat(detail.status()).isEqualTo("RESERVED");
        assertThat(detail.needsAction()).isTrue();
        assertThat(detail.version()).isEqualTo(2);
        assertThat(detail.purposeItemIds()).containsExactly(6L, 1L);
        assertThat(detail.purposeOther()).isTrue();
        assertThat(detail.purposeMemo()).isEqualTo("에어컨 소음");
    }

    @Test
    @DisplayName("needsAction 경계: 시각이 아직 안 지난 RESERVED·끝난(COMPLETED) 예약은 false")
    void needsActionBoundaries() {
        stored(LocalDateTime.of(2026, 11, 24, 15, 30), ReservationStatus.RESERVED, 0);
        assertThat(service().getOwnedDetail(SHOP, RES_ID).needsAction()).isFalse();

        stored(LocalDateTime.of(2026, 11, 20, 10, 0), ReservationStatus.COMPLETED, 1);
        ReservationDetail completed = service().getOwnedDetail(SHOP, RES_ID);
        assertThat(completed.needsAction()).isFalse();
        assertThat(completed.status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("목적 항목이 없으면 빈 목록")
    void noPurposeItems() {
        stored(LocalDateTime.of(2026, 11, 26, 10, 0), ReservationStatus.RESERVED, 0);
        when(itemRepository.findByShopIdAndReservationIdIn(SHOP, List.of(RES_ID))).thenReturn(List.of());

        assertThat(service().getOwnedDetail(SHOP, RES_ID).purposeItemIds()).isEmpty();
    }

    @Test
    @DisplayName("X3 없거나 다른 정비소 예약 → 404 NOT_FOUND (같은 응답), 항목은 읽지 않는다")
    void notOwned() {
        when(repository.findByIdAndShopId(RES_ID, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getOwnedDetail(9L, RES_ID))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
