package com.wanted.chagok.domain.reservation.dto;

import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import java.time.LocalDateTime;

/**
 * API 16 예약 등록 응답 (08 16번, P0-핵심). {@code assignee}는 P0-나머지부터라 없다.
 *
 * @param version 새 예약이라 0에서 시작한다
 */
public record ReservationCreateResponse(
        long reservationId,
        ReservationStatus status,
        LocalDateTime reservedAt,
        long vehicleId,
        int version) {

    /** 저장이 끝나 {@code id}·{@code version}이 채워진 Entity로 만든다. */
    public static ReservationCreateResponse of(Reservation reservation) {
        return new ReservationCreateResponse(reservation.getId(), reservation.getStatus(), reservation.getReservedAt(),
                reservation.getVehicleId(), reservation.getVersion());
    }
}
