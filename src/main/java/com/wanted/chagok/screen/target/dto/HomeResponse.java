package com.wanted.chagok.screen.target.dto;

import com.wanted.chagok.domain.reservation.api.ReservationAssignee;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API 04 홈 응답 (08 04번, 04 F-HOME-01). 값만 담는다 — 업무 판단은 각 도메인과 {@code TargetService}가 한다.
 *
 * <p>{@code todayReservations}는 15번의 항목과 같은 모양이고 시각 순이며 <b>취소를 포함</b>한다(상태 라벨로 구분, D-63 B05).
 * 각 항목에 담당 정비사 {@code assignee}가 있다(P0-나머지부터, 08 04번). 연락처는 P0-핵심에서 전체 번호다(D-40).
 *
 * @param date                  오늘 (Asia/Seoul)
 * @param todayReservationCount 오늘 예약 중 취소 제외 ("오늘 예약 N건")
 * @param todayContactCount     오늘 연락할 고객 수 ("오늘 연락할 고객 N명")
 */
public record HomeResponse(
    LocalDate date,
    int todayReservationCount,
    int todayContactCount,
    List<TodayReservation> todayReservations) {

    /**
     * @param status      예약 상태 이름
     * @param needsAction "처리 필요" (D-23, 서버가 판정한 값을 그대로)
     */
    public record TodayReservation(
        long reservationId,
        LocalDateTime reservedAt,
        String status,
        boolean needsAction,
        int version,
        long vehicleId,
        String plateNumber,
        long customerId,
        String customerName,
        String phone,
        String purposeSummary,
        ReservationAssignee assignee) {
    }
}
