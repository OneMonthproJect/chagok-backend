package com.wanted.chagok.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * API 39 방문 접수 요청 (08 39번, 04 F-RSV-09). {@code version}은 18과 같다: 빠지면 400 {@code INVALID_INPUT},
 * 서버 값과 다르면 409 {@code CONCURRENT_MODIFICATION}.
 *
 * <p>{@code modelYear}·{@code mileage}는 차량의 연식·최근 주행거리가 비어 있을 때만 필수다(D-58, 없으면 400).
 * 범위(연식 1980 ~ 현재 연도 + 1, 주행거리 0 ~ 2,000,000)는 customer가 한 곳에서 확인한다.
 *
 * @param version   04·15·17 응답의 {@code version}
 * @param modelYear 차량 연식이 비어 있을 때만 필수
 * @param mileage   차량 최근 주행거리가 비어 있을 때만 필수
 */
public record ReservationStartRequest(
        @NotNull(message = "필수 입력입니다.")
        Integer version,
        Integer modelYear,
        Integer mileage) {
}
