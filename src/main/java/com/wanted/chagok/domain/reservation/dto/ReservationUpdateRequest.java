package com.wanted.chagok.domain.reservation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * API 19 예약 변경 요청 (08 19번, P0-나머지). 보낸 필드만 바꾼다 — 빠졌거나 null이면 그대로다. 고객·차량은 바꿀 수 없어 필드가 없다.
 * 요청에 {@code shopId}가 와도 읽지 않는다 (X8). 담당 재배정은 P0-나머지부터 {@code assigneeId}로 한다 (D-53).
 *
 * <p>{@code purpose.memo}는 null이면 그대로이고 비우려면 빈 문자열을 보낸다(08에 정해진 바가 없어 정한 값).
 * {@code purpose.itemIds}를 보내면 예약 목적 항목 전체를 이 목록으로 바꾼다(빈 목록이면 모두 지움).
 *
 * @param version        화면이 읽은 예약의 {@code version}. 서버 값과 다르면 409 (D-42)
 * @param reservedAt     분이 00·30, 초 00, 날짜 ≥ 오늘 (16과 같음)
 * @param purpose        예약 목적. 모양은 16과 같다
 * @param assigneeId     담당 재배정. 그 정비소의 사장·정비사여야 한다 (X13). 지금 담당과 같으면 바꾸는 것이 아니다
 * @param reassignReason 재배정 사유, ~200자. 당일 사장 재배정이면 필수 (R24, {@code AssigneeChangePolicy})
 */
public record ReservationUpdateRequest(
        @NotNull(message = "필수 입력입니다.")
        Integer version,
        LocalDateTime reservedAt,
        @Valid
        ReservationCreateRequest.Purpose purpose,
        Long assigneeId,
        @Size(max = 200, message = "200자 이하로 입력해 주세요.")
        String reassignReason) {

    /** {@code version} 밖에 바꿀 필드가 하나라도 있는가 (08 19번 R14). 재배정({@code assigneeId})도 바꿀 필드다. */
    public boolean hasChange() {
        return assigneeId != null || hasChangeExceptAssignee();
    }

    /** 일시·예약 목적 중 바꿀 것이 있는가. 담당이 지금과 같게 온 요청이 실제로 바뀌는 게 있는지 가를 때 쓴다. */
    public boolean hasChangeExceptAssignee() {
        if (reservedAt != null) {
            return true;
        }
        return purpose != null && (purpose.itemIds() != null || purpose.other() != null || purpose.memo() != null);
    }
}
