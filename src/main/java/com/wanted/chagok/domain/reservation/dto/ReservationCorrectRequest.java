package com.wanted.chagok.domain.reservation.dto;

import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * API 40 지난 예약 정정 요청 (08 7.1 40번, D-53). 끝난 예약의 상태·예약 메모를 사후에 고친다. 요청에 {@code shopId}가 와도 읽지 않는다 (X8).
 *
 * <p>{@code status}·{@code purposeMemo} 중 바꿀 것을 보낸다. 지금 값과 같은 필드는 바꾸는 것이 아니고, 바뀌는 필드가 하나도 없으면 400이다
 * (service). {@code status}가 {@code COMPLETED}·{@code CANCELED}·{@code NO_SHOW} 밖이거나 알 수 없는 값이면 400이다.
 *
 * @param version     18~21과 같음. 서버 값과 다른 숫자(음수 포함)는 400이 아니라 409 {@code CONCURRENT_MODIFICATION}
 * @param status      바꿀 상태. 없으면 상태는 그대로
 * @param purposeMemo 바꿀 예약 메모, ~200자. 없으면 그대로이고 빈 문자열이면 비운다(19와 같음)
 * @param reason      정정 사유, ~200자. 공백뿐이면 사유 없음으로 본다
 */
public record ReservationCorrectRequest(
        @NotNull(message = "필수 입력입니다.")
        Integer version,
        ReservationStatus status,
        @Size(max = 200, message = "200자 이하로 입력해 주세요.")
        String purposeMemo,
        @Size(max = 200, message = "200자 이하로 입력해 주세요.")
        String reason) {
}
