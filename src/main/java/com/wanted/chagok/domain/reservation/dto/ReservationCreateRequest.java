package com.wanted.chagok.domain.reservation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API 16 예약 등록 요청 (08 16번, P0-나머지). 요청에 {@code shopId}가 없다 — 정비소는 토큰에서 온다 (08 0.1).
 * 본문에 {@code shopId}가 와도 읽지 않는다 (X8). 담당 정비사는 P0-나머지부터 {@code assigneeId}로 받는다 (D-51).
 *
 * <p>여기서는 형식(필수·길이)만 본다. 30분 단위·항목·담당·날짜 검증은 차량 소유 확인 뒤 service가 08 16번의 순서대로 한다.
 *
 * @param vehicleId  그 정비소 차량. 0·음수처럼 없는 번호도 형식 오류가 아니라 소유 확인에서 404다 (08 0.1)
 * @param reservedAt 분이 00·30, 초 00 (R1), 날짜 ≥ 오늘 (R2) — service
 * @param assigneeId 담당 정비사. 없으면 400 (R19). 그 정비소의 사장·정비사가 아니면 없는 id와 같은 400이다 (R20, X13) — service
 * @param purpose    예약 목적. 없으면 항목 없음·기타 아님·메모 없음
 */
public record ReservationCreateRequest(
        @NotNull(message = "필수 입력입니다.")
        Long vehicleId,
        @NotNull(message = "필수 입력입니다.")
        LocalDateTime reservedAt,
        @NotNull(message = "필수 입력입니다.")
        Long assigneeId,
        @Valid
        Purpose purpose) {

    /**
     * @param itemIds 그 정비소의 쓰는 항목, 중복 없음 (X11). 없으면 빈 목록으로 본다
     * @param other   기본 false
     * @param memo    ~200자
     */
    public record Purpose(
            List<Long> itemIds,
            Boolean other,
            @Size(max = 200, message = "200자 이하로 입력해 주세요.")
            String memo) {
    }
}
