package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.reservation.api.ReservationDay;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationItem;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 홈의 오늘 예약용 {@code ReservationReader.findByDate} (08 04번, D-63 B05) — Repository·customer·item은 가짜(DB 없음).
 * 하루치 조회 기간, 15번과 같은 모양으로 옮기기, 건수(취소 제외)와 목록(취소 포함)의 구분 자리, 빈 날을 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationFindByDateServiceTest {

    private static final long SHOP = 3L;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    @Mock
    private ReservationRepository repository;

    @Mock
    private ReservationItemRepository itemRepository;

    @Mock
    private CustomerReader customerReader;

    @Mock
    private CustomerWriter customerWriter;

    @Mock
    private ItemReader itemReader;
    @Mock
    private StaffReader staffReader;

    private ReservationService service() {
        return new ReservationService(repository, itemRepository, customerReader, customerWriter, itemReader, staffReader, clock);
    }

    private static Reservation reservation(long id, long vehicleId, LocalDateTime at, ReservationStatus status,
                                           boolean other, String memo) {
        Reservation r = Reservation.register(SHOP, vehicleId, at, other, memo, 7L);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", 0);
        return r;
    }

    @Test
    @DisplayName("그날 0시부터 다음 날 0시 전까지 한 번에 읽고, 15번과 같은 모양(요약·목적 이름·needsAction)으로 시각 순 그대로 옮긴다")
    void mapsTheDayLikeTheList() {
        Reservation morning = reservation(1, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, false, null);
        Reservation afternoon = reservation(2, 7, LocalDateTime.of(2026, 11, 24, 14, 30), ReservationStatus.RESERVED, true, "에어컨 냄새");
        when(repository.findInPeriod(SHOP, LocalDate.of(2026, 11, 24).atStartOfDay(), LocalDate.of(2026, 11, 25).atStartOfDay()))
            .thenReturn(List.of(morning, afternoon));
        when(customerReader.findVehicleSummaries(SHOP, Set.of(4L, 7L))).thenReturn(Map.of(
            4L, new VehicleSummary(4, "78라0004", 40, "최지은", "010-0000-0004", true),
            7L, new VehicleSummary(7, "22사0007", 70, "서민준", "010-0000-0007", true)));
        when(itemRepository.findByShopIdAndReservationIdIn(SHOP, List.of(1L, 2L)))
            .thenReturn(List.of(ReservationItem.of(SHOP, 1, 1L)));
        when(itemReader.findItems(SHOP, Set.of(1L)))
            .thenReturn(Map.of(1L, new ItemRef(1L, "ENGINE_OIL", "엔진오일", 6, 10000, true, 1)));

        ReservationDay day = service().findByDate(SHOP, DAY);

        assertThat(day.reservations()).extracting("reservationId").containsExactly(1L, 2L);
        assertThat(day.reservations().get(0).plateNumber()).isEqualTo("78라0004");
        assertThat(day.reservations().get(0).customerName()).isEqualTo("최지은");
        assertThat(day.reservations().get(0).phone()).isEqualTo("010-0000-0004");
        assertThat(day.reservations().get(0).status()).isEqualTo("RESERVED");
        assertThat(day.reservations().get(0).purposeSummary()).isEqualTo("엔진오일");
        // 10:00은 지금(15:00)보다 이전이라 "처리 필요"이고, 14:30도 그렇다
        assertThat(day.reservations()).extracting("needsAction").containsExactly(true, true);
        assertThat(day.reservations().get(1).purposeSummary()).isEqualTo("기타");
    }

    @Test
    @DisplayName("건수는 취소를 뺀다 — 끝난(COMPLETED)·예약(RESERVED)은 세고, 취소(CANCELED)는 목록에만 있고 세지 않는다")
    void countExcludesCancelledButListKeepsThem() {
        when(repository.findInPeriod(anyLong(), any(), any()))
            .thenReturn(List.of(
                reservation(1, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.COMPLETED, false, null),
                reservation(2, 4, LocalDateTime.of(2026, 11, 24, 16, 0), ReservationStatus.RESERVED, false, null),
                reservation(3, 4, LocalDateTime.of(2026, 11, 24, 17, 0), ReservationStatus.CANCELED, false, null)));
        when(customerReader.findVehicleSummaries(anyLong(), anyCollection())).thenReturn(Map.of(
            4L, new VehicleSummary(4, "78라0004", 40, "최지은", "010-0000-0004", true)));
        when(itemRepository.findByShopIdAndReservationIdIn(anyLong(), anyCollection())).thenReturn(List.of());

        ReservationDay day = service().findByDate(SHOP, DAY);

        assertThat(day.countExcludingCancelled()).isEqualTo(2);
        assertThat(day.reservations()).hasSize(3);
        assertThat(day.reservations()).extracting("status").containsExactly("COMPLETED", "RESERVED", "CANCELED");
        // 끝난 예약은 시각이 지나도 "처리 필요"가 아니고, 아직 안 지난 16:00 RESERVED·취소도 아니다
        assertThat(day.reservations()).extracting("needsAction").containsExactly(false, false, false);
    }

    @Test
    @DisplayName("건수는 취소만 뺀다 — 노쇼(NO_SHOW)·진행중(IN_PROGRESS)은 세고, 시각이 지나도 \"처리 필요\"가 아니다 (08 04번, D-23)")
    void countKeepsNoShowAndInProgress() {
        when(repository.findInPeriod(anyLong(), any(), any()))
            .thenReturn(List.of(
                reservation(1, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.NO_SHOW, false, null),
                reservation(2, 4, LocalDateTime.of(2026, 11, 24, 11, 0), ReservationStatus.IN_PROGRESS, false, null),
                reservation(3, 4, LocalDateTime.of(2026, 11, 24, 12, 0), ReservationStatus.CANCELED, false, null)));
        when(customerReader.findVehicleSummaries(anyLong(), anyCollection())).thenReturn(Map.of(
            4L, new VehicleSummary(4, "78라0004", 40, "최지은", "010-0000-0004", true)));
        when(itemRepository.findByShopIdAndReservationIdIn(anyLong(), anyCollection())).thenReturn(List.of());

        ReservationDay day = service().findByDate(SHOP, DAY);

        assertThat(day.countExcludingCancelled()).isEqualTo(2);
        assertThat(day.reservations()).extracting("status").containsExactly("NO_SHOW", "IN_PROGRESS", "CANCELED");
        assertThat(day.reservations()).extracting("needsAction").containsExactly(false, false, false);
    }

    @Test
    @DisplayName("예약이 없는 날은 건수 0·빈 목록이고 차량·항목은 읽지 않는다")
    void emptyDay() {
        when(repository.findInPeriod(anyLong(), any(), any()))
            .thenReturn(List.of());

        ReservationDay day = service().findByDate(SHOP, DAY);

        assertThat(day.countExcludingCancelled()).isZero();
        assertThat(day.reservations()).isEmpty();
        verifyNoInteractions(customerReader, itemReader, itemRepository);
    }

    @Test
    @DisplayName("정비소는 인자의 shopId로만 조회한다(차량·고객 요약도 같은 정비소로)")
    void readsWithTheShopId() {
        Reservation r = reservation(1, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, false, null);
        when(repository.findInPeriod(anyLong(), any(), any()))
            .thenReturn(List.of(r));
        when(customerReader.findVehicleSummaries(anyLong(), anyCollection())).thenReturn(Map.of(
            4L, new VehicleSummary(4, "78라0004", 40, "최지은", "010-0000-0004", true)));
        when(itemRepository.findByShopIdAndReservationIdIn(anyLong(), anyCollection())).thenReturn(List.of());

        service().findByDate(SHOP, DAY);

        verify(repository).findInPeriod(SHOP, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay());
        verify(customerReader).findVehicleSummaries(SHOP, Set.of(4L));
    }
}
