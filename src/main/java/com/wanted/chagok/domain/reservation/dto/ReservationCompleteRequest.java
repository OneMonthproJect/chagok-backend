package com.wanted.chagok.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * API 18 방문 완료 요청 (08 18번). 화면이 읽은 예약의 {@code version}을 그대로 보낸다 (D-42, R13).
 * 빠지면 400 {@code INVALID_INPUT}이고 예약은 바뀌지 않는다. 숫자가 아닌 값은 JSON 읽기 단계에서 같은 400이 된다.
 * 서버 값과 다른 숫자(음수 포함)는 400이 아니라 409 {@code CONCURRENT_MODIFICATION}이다 — 08은 그렇게만 정한다.
 *
 * @param version 04·15·17 응답의 {@code version}. {@code Integer}인 것은 누락을 null로 구별하려는 것이다
 */
public record ReservationCompleteRequest(
        @NotNull(message = "필수 입력입니다.")
        Integer version) {
}
