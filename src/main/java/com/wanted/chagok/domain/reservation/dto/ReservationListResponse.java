package com.wanted.chagok.domain.reservation.dto;

import com.wanted.chagok.domain.reservation.api.ReservationAssignee;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API 15 기간 조회 응답 (08 15번). 쪽 나누기 없이 기간 전체를 돌려주고, 예약이 없으면 {@code items}는 {@code []}다.
 * 각 항목에 담당 정비사 {@code assignee}가 있다 (P0-나머지부터, 08 15번 표). 연락처는 P0-핵심에서 전체 번호다 (08 0.5).
 *
 * @param from  요청 기간 그대로
 * @param to    요청 기간 그대로
 * @param items 예약 시각 순
 */
public record ReservationListResponse(LocalDate from, LocalDate to, List<Item> items) {

    /**
     * @param needsAction    {@code RESERVED}인데 시각이 지남 (D-23)
     * @param version        변경(19)·방문 완료(18) 요청에 그대로 보낸다 (D-42)
     * @param purposeSummary 고른 항목 이름을 {@code ·}로 잇고 "기타"를 붙인다. 없으면 null
     * @param assignee       담당 정비사 (P0-나머지부터, 08 15번 표). {@code colorIndex}는 API 36과 같은 값
     */
    public record Item(
            long reservationId,
            LocalDateTime reservedAt,
            ReservationStatus status,
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
