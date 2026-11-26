package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.item.api.ItemReader;
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
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * API 20 취소·21 노쇼의 Service 단위 테스트 — Repository는 가짜(DB 없음). 정비소 격리(X12)와 호출 순서·응답 모양을 본다.
 * 저장 시 {@code @Version} 증가와 동시 요청은 DB가 필요해 {@code ReservationConcurrencyDbTest}가 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationCancelNoShowServiceTest {

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

    private static Reservation reservation(long id, long shopId, LocalDateTime at, ReservationStatus status,
                                           int version) {
        Reservation r = Reservation.register(shopId, 4L, at, false, null, 7L);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", version);
        return r;
    }

    @Test
    @DisplayName("취소: 소유 확인 → 전이 → 저장(flush), 응답은 08 20번 모양(CANCELED, reservedAt 없음)")
    void cancel() {
        Reservation r = reservation(3, 3, LocalDateTime.of(2026, 11, 26, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(3L, 3L)).thenReturn(Optional.of(r));

        ReservationResponse response = service().cancel(3L, 3L, 0);

        assertThat(response.reservationId()).isEqualTo(3L);
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(response.vehicleId()).isEqualTo(4L);
        assertThat(response.reservedAt()).isNull();
        verify(repository).saveAndFlush(r);
    }

    @Test
    @DisplayName("취소: 시각이 지난 예약도 취소된다 (시각 전·후 모두)")
    void cancelPastTime() {
        Reservation r = reservation(3, 3, LocalDateTime.of(2026, 11, 20, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(3L, 3L)).thenReturn(Optional.of(r));

        assertThat(service().cancel(3L, 3L, 0).status()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("X12 취소: 다른 정비소 예약 id → 404 NOT_FOUND, 저장 없음")
    void cancelOtherShop() {
        when(repository.findByIdAndShopId(3L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().cancel(9L, 3L, 0))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("취소: 끝난 예약 → 409 INVALID_STATUS_TRANSITION, 저장 없음")
    void cancelFinished() {
        Reservation r = reservation(3, 3, LocalDateTime.of(2026, 11, 20, 10, 0), ReservationStatus.COMPLETED, 1);
        when(repository.findByIdAndShopId(3L, 3L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service().cancel(3L, 3L, 1))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("노쇼: Clock 기준 지난 예약 → NO_SHOW, 응답은 08 21번 모양")
    void noShow() {
        Reservation r = reservation(4, 3, LocalDateTime.of(2026, 11, 24, 14, 30), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(4L, 3L)).thenReturn(Optional.of(r));

        ReservationResponse response = service().markNoShow(3L, 4L, 0);

        assertThat(response.status()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(response.reservationId()).isEqualTo(4L);
        verify(repository).saveAndFlush(r);
    }

    @Test
    @DisplayName("R6 노쇼: Clock 기준 아직 안 지난 예약 → 409 INVALID_STATUS_TRANSITION, 저장 없음")
    void noShowBeforeTime() {
        Reservation r = reservation(4, 3, LocalDateTime.of(2026, 11, 24, 15, 30), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(4L, 3L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service().markNoShow(3L, 4L, 0))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("X12 노쇼: 다른 정비소 예약 id → 404 NOT_FOUND, 저장 없음")
    void noShowOtherShop() {
        when(repository.findByIdAndShopId(4L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().markNoShow(9L, 4L, 0))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(repository, never()).saveAndFlush(any());
    }
}
