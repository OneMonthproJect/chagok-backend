package com.wanted.chagok.screen.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.reservation.api.ReservationAssignee;
import com.wanted.chagok.domain.reservation.api.ReservationDay;
import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.domain.reservation.api.ReservationSummary;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.screen.target.dto.HomeResponse;
import com.wanted.chagok.screen.target.dto.TargetListResponse;
import com.wanted.chagok.screen.target.service.HomeService;
import com.wanted.chagok.screen.target.service.TargetService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * API 04 홈 조립 (08 04번, 04 F-HOME-01) — reservation.api와 TargetService는 가짜(DB 없음).
 * 조립만 본다: 오늘 예약 건수·목록은 reservation 값 그대로, "오늘 연락할 고객" 수의 기준(예약 없는 대상 차량이 있는 고객, 한 명으로 센다).
 */
@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

    private static final long SHOP = 3L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    @Mock
    private ReservationReader reservationReader;

    @Mock
    private TargetService targetService;

    private HomeService service() {
        return new HomeService(reservationReader, targetService, clock);
    }

    private static TargetListResponse.Item target(long customerId, long vehicleId, boolean reserved) {
        return new TargetListResponse.Item(customerId, "고객" + customerId, "010-0000-000" + customerId, vehicleId,
            "12가000" + vehicleId,
            List.of(new TargetListResponse.TargetItem(1L, "엔진오일", ItemStatus.DUE, TODAY.minusDays(3), TODAY.minusMonths(6))),
            reserved ? new TargetListResponse.ActiveReservation(100 + vehicleId, TODAY.plusDays(2).atTime(10, 0)) : null);
    }

    private void stub(ReservationDay day, List<TargetListResponse.Item> targets) {
        when(reservationReader.findByDate(SHOP, TODAY)).thenReturn(day);
        when(targetService.getTargets(SHOP)).thenReturn(new TargetListResponse(TODAY, targets));
    }

    @Test
    @DisplayName("오늘 예약 건수·목록은 reservation 값 그대로(시각 순, 모양 15번과 같음), date는 오늘")
    void reservationsAreCarriedAsTheyAre() {
        ReservationSummary first = new ReservationSummary(1, TODAY.atTime(10, 0), "RESERVED", true, 0, 4, "78라0004", 40,
            "최지은", "010-0000-0004", "엔진오일", new ReservationAssignee(1L, "박사장", 0));
        ReservationSummary second = new ReservationSummary(2, TODAY.atTime(14, 30), "RESERVED", true, 0, 7, "22사0007", 70,
            "서민준", "010-0000-0007", "기타", new ReservationAssignee(2L, "김정비", 1));
        stub(new ReservationDay(2, List.of(first, second)), List.of());

        HomeResponse home = service().getHome(SHOP);

        assertThat(home.date()).isEqualTo(TODAY);
        assertThat(home.todayReservationCount()).isEqualTo(2);
        assertThat(home.todayReservations()).extracting(HomeResponse.TodayReservation::reservationId).containsExactly(1L, 2L);
        HomeResponse.TodayReservation a = home.todayReservations().get(0);
        assertThat(a.reservedAt()).isEqualTo(TODAY.atTime(10, 0));
        assertThat(a.status()).isEqualTo("RESERVED");
        assertThat(a.needsAction()).isTrue();
        assertThat(a.version()).isZero();
        assertThat(a.plateNumber()).isEqualTo("78라0004");
        assertThat(a.customerName()).isEqualTo("최지은");
        assertThat(a.phone()).isEqualTo("010-0000-0004");
        assertThat(a.purposeSummary()).isEqualTo("엔진오일");
    }

    @Test
    @DisplayName("건수는 목록에서 다시 세지 않고 reservation이 준 값을 쓴다 (취소는 목록에 있어도 건수에서 뺀 값)")
    void countIsNotRecountedFromTheList() {
        ReservationSummary r = new ReservationSummary(1, TODAY.atTime(10, 0), "RESERVED", false, 0, 4, "78라0004", 40,
            "최지은", "010-0000-0004", null, new ReservationAssignee(1L, "박사장", 0));
        // 목록은 3줄(취소 포함)이지만 건수는 2(취소 제외)라는 reservation의 값을 그대로 쓴다
        stub(new ReservationDay(2, List.of(r, r, r)), List.of());

        HomeResponse home = service().getHome(SHOP);

        assertThat(home.todayReservations()).hasSize(3);
        assertThat(home.todayReservationCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("오늘 연락할 고객: 예약 없는 대상 차량이 있는 고객만 세고, 한 고객의 차량이 여럿이어도 한 명으로 센다")
    void contactCountRules() {
        stub(new ReservationDay(0, List.of()), List.of(
            target(1, 1, false), target(1, 2, false),      // 고객 1: 예약 없는 차량 둘 → 한 명
            target(2, 3, true),                            // 고객 2: 대상 차량이 모두 예약 있음 → 세지 않음
            target(3, 4, false), target(3, 5, true),       // 고객 3: 예약 없는 차량 하나라도 있으면 센다
            target(4, 6, true)));                          // 고객 4: 예약 있음뿐 → 세지 않음

        HomeResponse home = service().getHome(SHOP);

        assertThat(home.todayContactCount()).isEqualTo(2);   // 고객 1, 3
    }

    @Test
    @DisplayName("대상이 없으면 연락할 고객 0명, 예약이 없으면 건수 0·빈 목록")
    void empty() {
        stub(new ReservationDay(0, List.of()), List.of());

        HomeResponse home = service().getHome(SHOP);

        assertThat(home.todayContactCount()).isZero();
        assertThat(home.todayReservationCount()).isZero();
        assertThat(home.todayReservations()).isEmpty();
    }

    @Test
    @DisplayName("모든 조회는 토큰의 정비소로 가고, 예약은 오늘 날짜로 한 번 읽는다")
    void readsOnceWithTheShopId() {
        stub(new ReservationDay(0, List.of()), List.of());

        service().getHome(SHOP);

        verify(reservationReader).findByDate(SHOP, TODAY);
        verify(targetService).getTargets(SHOP);
    }
}
