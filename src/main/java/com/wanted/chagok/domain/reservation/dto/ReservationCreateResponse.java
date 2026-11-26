package com.wanted.chagok.domain.reservation.dto;

import com.wanted.chagok.domain.reservation.api.ReservationAssignee;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import java.time.LocalDateTime;

/**
 * API 16 예약 등록 응답 (08 16번). P0-나머지부터 {@code assignee}(담당 정비사)가 있다.
 *
 * @param version  새 예약이라 0에서 시작한다
 * @param assignee 담당 정비사. 이름·{@code colorIndex}는 API 36과 같은 값
 */
public record ReservationCreateResponse(
        long reservationId,
        ReservationStatus status,
        LocalDateTime reservedAt,
        long vehicleId,
        int version,
        ReservationAssignee assignee) {

    /** 저장이 끝나 {@code id}·{@code version}이 채워진 Entity와 그 담당 정비사로 만든다. */
    public static ReservationCreateResponse of(Reservation reservation, ReservationAssignee assignee) {
        return new ReservationCreateResponse(reservation.getId(), reservation.getStatus(), reservation.getReservedAt(),
                reservation.getVehicleId(), reservation.getVersion(), assignee);
    }
}
