package com.wanted.chagok.domain.reservation.api;

import java.time.LocalDateTime;

/**
 * 다른 패키지에 내보내는 예약 참조. Entity가 아니라 값이다 (CONVENTIONS 2장).
 * 필드는 08 14번 응답의 {@code activeReservation}({@code reservationId}·{@code reservedAt})에 {@code vehicleId}를
 * 더한 것이라, 호출하는 쪽은 값을 그대로 옮기기만 하면 된다.
 *
 * @param reservationId 예약 번호
 * @param vehicleId     그 예약의 차량
 * @param reservedAt    예약 일시 (Asia/Seoul, 시간대 표기 없음)
 */
public record ReservationRef(long reservationId, long vehicleId, LocalDateTime reservedAt) {
}
