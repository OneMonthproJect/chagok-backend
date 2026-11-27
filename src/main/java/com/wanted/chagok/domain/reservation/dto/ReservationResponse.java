package com.wanted.chagok.domain.reservation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wanted.chagok.domain.reservation.api.ReservationAssignee;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import java.time.LocalDateTime;

/**
 * 예약 상태·일시를 바꾸는 API의 응답 (08 18·19·39번). 18·20·21·39는 {@code reservedAt}이 없고 19만 있다 — 값이 null인 필드는 JSON에서 뺀다.
 *
 * @param reservationId 예약 번호
 * @param status        바뀐 뒤 상태
 * @param vehicleId     화면이 방문·정비 기록(S-08)을 열 때 쓴다 (18)
 * @param version       바뀐 {@code version} (요청 값 + 1)
 * @param reservedAt    19번 응답에만 있다. 18·20·21·39는 null이라 JSON에서 빠진다 (08 39는 4필드)
 * @param assignee      19번 응답에만 있다(P0-나머지부터, 08 19번 예시). 18·20·21·39는 null이라 JSON에서 빠진다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReservationResponse(
        long reservationId,
        ReservationStatus status,
        long vehicleId,
        int version,
        LocalDateTime reservedAt,
        ReservationAssignee assignee) {

    /** API 18 응답: {@code reservedAt} 없음. 저장(flush)이 끝나 {@code version}이 올라간 Entity로 만든다. */
    public static ReservationResponse statusChanged(Reservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getStatus(), reservation.getVehicleId(),
                reservation.getVersion(), null, null);
    }

    /** API 19 응답: 변경 뒤 일시와 담당 정비사를 포함한다 (08 19번 예시). */
    public static ReservationResponse changed(Reservation reservation, ReservationAssignee assignee) {
        return new ReservationResponse(reservation.getId(), reservation.getStatus(), reservation.getVehicleId(),
                reservation.getVersion(), reservation.getReservedAt(), assignee);
    }
}
