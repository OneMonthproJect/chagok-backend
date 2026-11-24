package com.wanted.chagok.domain.reservation.service;

import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.domain.reservation.api.ReservationRef;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 유스케이스. 다른 도메인 계약({@code ReservationReader})을 구현하고, 방문 완료(API 18)를 한다.
 * 예약 조회·등록(API 15·16)과 변경(API 19)은 customer·item 계약이 머지된 뒤 이 클래스에 메서드를 더한다.
 */
@Service
public class ReservationService implements ReservationReader {

    private final ReservationRepository reservationRepository;
    private final Clock clock;

    public ReservationService(ReservationRepository reservationRepository, Clock clock) {
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    /**
     * 유효 예약 = {@code RESERVED} + 예약 날짜 ≥ 오늘 (D-22). 오늘은 {@code Clock}(Asia/Seoul)으로 구한다.
     * 한 차량에 둘 이상이 나와도 예외 없이 가장 이른 것 하나만 남긴다(등록 때 막는 규칙이고 DB 제약은 없다).
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, ReservationRef> findActiveByVehicleIds(long shopId, Collection<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        LocalDate today = LocalDate.now(clock);
        Map<Long, ReservationRef> active = new LinkedHashMap<>();
        // 이른 순서로 오므로 차량마다 처음 만난 것이 가장 이른 예약이다
        for (Reservation r : reservationRepository.findByVehiclesAndStatusFrom(
                shopId, vehicleIds, ReservationStatus.RESERVED, today.atStartOfDay())) {
            active.putIfAbsent(r.getVehicleId(), new ReservationRef(r.getId(), r.getVehicleId(), r.getReservedAt()));
        }
        return active;
    }

    /**
     * API 18 방문 완료 (04 F-RSV-04, R5·R10·R12·R13). 순서: 정비소 소유(404) → 상태(409 INVALID_STATUS_TRANSITION) →
     * 요청 version 비교(409 CONCURRENT_MODIFICATION) → 전이 → {@code @Version} 조건 저장. 하나라도 어긋나면
     * 예외가 나고 트랜잭션이 되돌려져 상태·{@code version}이 그대로다.
     */
    @Transactional
    public ReservationResponse complete(long shopId, long reservationId, int expectedVersion) {
        Reservation reservation = getOwned(shopId, reservationId);
        reservation.complete(expectedVersion);
        // 읽은 뒤 저장 사이에 다른 요청이 먼저 바꿨으면 여기서 충돌이 드러나 409가 된다. flush 뒤에야 version이 올라간다
        reservationRepository.saveAndFlush(reservation);
        return ReservationResponse.statusChanged(reservation);
    }

    /** 정비소 소속 확인의 한 곳. 없거나 다른 정비소 것이면 같은 404 (CONVENTIONS 4장, 08 X-규칙). */
    private Reservation getOwned(long shopId, long reservationId) {
        return reservationRepository.findByIdAndShopId(reservationId, shopId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
