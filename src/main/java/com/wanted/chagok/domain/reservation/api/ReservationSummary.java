package com.wanted.chagok.domain.reservation.api;

import java.time.LocalDateTime;

/**
 * 목록 줄 표시용 예약 요약 (홈의 오늘 예약 목록, 08 04번 {@code todayReservations}). 모양은 08 15번의 항목과 같다.
 * Entity가 아니라 값이다 (CONVENTIONS 2장). 연락처는 P0-핵심에서 전체 번호다 (08 0.5). {@code assignee}는 P0-나머지부터 있다(담당 정비사, 08 15번 표).
 *
 * @param status         예약 상태 이름 ({@code RESERVED}·{@code COMPLETED}, P0-나머지부터 더 있음. Entity enum을 내보내지 않으려고 문자열)
 * @param needsAction    "처리 필요" (D-23): 상태가 {@code RESERVED}인데 예약 시각이 현재보다 이전. 판정은 reservation 한 곳
 * @param version        변경(19)·방문 완료(18) 요청에 그대로 보낸다 (D-42)
 * @param purposeSummary 고른 항목 이름을 {@code ·}로 잇고 "기타"를 붙인다. 없으면 null
 * @param assignee       담당 정비사 (P0-나머지부터, {@code colorIndex}는 API 36과 같은 값)
 */
public record ReservationSummary(
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
    ReservationAssignee assignee
) {
}
