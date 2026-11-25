package com.wanted.chagok.domain.reservation.dto;

import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;

/**
 * 예약 상태를 바꾸는 API의 응답 (08 18번: 방문 완료). 19번(변경)은 {@code reservedAt}이 더 있어, 그 이슈에서 필드를 더한다.
 *
 * @param reservationId 예약 번호
 * @param status        바뀐 뒤 상태
 * @param vehicleId     화면이 방문·정비 기록(S-08)을 열 때 쓴다
 * @param version       바뀐 {@code version} (요청 값 + 1)
 */
public record ReservationResponse(
        long reservationId,
        ReservationStatus status,
        long vehicleId,
        int version) {

    /** 저장(flush)이 끝나 {@code version}이 올라간 Entity로 만든다. */
    public static ReservationResponse statusChanged(Reservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getStatus(), reservation.getVehicleId(),
                reservation.getVersion());
    }
}
