package com.wanted.chagok.screen.target.service;

import com.wanted.chagok.domain.reservation.api.ReservationDay;
import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.screen.target.dto.HomeResponse;
import com.wanted.chagok.screen.target.dto.TargetListResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 04 홈 조립 (08 04번, 04 F-HOME-01). 저장·상태 변경 없이 같은 {@code screen/target}의 {@link TargetService}와
 * {@code reservation.api}를 읽어 한 응답으로 만든다(CONVENTIONS 5장). 상태 판정·동의 판단을 여기서 다시 하지 않는다.
 *
 * <p>"오늘 연락할 고객 N명" = 재점검 대상 목록(API 14)에 나오는 고객 중 <b>예약 없는 대상 차량이 하나라도 있는</b> 고객 수다.
 * 그 고객의 대상 차량이 모두 "예약 있음"이면 세지 않고, 한 고객의 차량이 여럿이어도 한 명으로 센다.
 * 같은 목록을 쓰므로 홈 숫자와 S-04 목록은 항상 같은 기준이다("누르면 S-04").
 */
@Service
@Transactional(readOnly = true)
public class HomeService {

    private final ReservationReader reservationReader;
    private final TargetService targetService;
    private final Clock clock;

    public HomeService(ReservationReader reservationReader, TargetService targetService, Clock clock) {
        this.reservationReader = reservationReader;
        this.targetService = targetService;
        this.clock = clock;
    }

    // LIMIT: 대상 목록 전체를 한 번 만들어 고객 수만 센다. 정비소당 차량 수백 대 이하 가정(08 14번) / 넘으면 14번과 함께 정한다
    public HomeResponse getHome(long shopId) {
        LocalDate today = LocalDate.now(clock);
        ReservationDay day = reservationReader.findByDate(shopId, today);
        TargetListResponse targets = targetService.getTargets(shopId);

        int contactCount = (int) targets.items().stream()
            .filter(item -> item.activeReservation() == null)
            .map(TargetListResponse.Item::customerId)
            .distinct()
            .count();

        List<HomeResponse.TodayReservation> reservations = day.reservations().stream()
            .map(r -> new HomeResponse.TodayReservation(r.reservationId(), r.reservedAt(), r.status(), r.needsAction(),
                r.version(), r.vehicleId(), r.plateNumber(), r.customerId(), r.customerName(), r.phone(),
                r.purposeSummary(), r.assignee()))
            .toList();
        return new HomeResponse(today, day.countExcludingCancelled(), contactCount, reservations);
    }
}
