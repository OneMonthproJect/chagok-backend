package com.wanted.chagok.domain.maintenance.dto;

import com.wanted.chagok.domain.maintenance.entity.RecordType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * API 37 요청 (08 37번). 보낸 필드만 바꾼다({@code null} = 보내지 않음). 차량은 바꿀 수 없다. 요청에 {@code shopId}가 없다 —
 * 정비소는 토큰에서 정한다(X8). 형식(필수·길이)은 여기서, 범위·권한·항목·담당은 {@code MaintenanceService}가 본다.
 *
 * @param version       화면이 읽은 기록의 {@code version} (11 {@code records[].version}). 다르면 409
 * @param recordDate    바꿀 방문일. 오늘 이전·오늘
 * @param mileage       바꿀 주행거리, 정수 0~2,000,000
 * @param type          바꿀 구분
 * @param itemId        바꿀 항목. 그 정비소의 쓰는 항목 (X11)
 * @param inspectResult {@code GOOD}·{@code CAUTION}. 수정 뒤 구분이 {@code INSPECT}일 때만
 * @param memo          ~200자. 빈 문자열이면 메모를 비운다
 * @param mechanicId    바꿀 담당 정비사. 그 정비소 직원 (X13)
 * @param reason        수정 사유 ~200자. 공백뿐이면 사유 없음
 */
public record RecordEditRequest(
    @NotNull(message = "필수 입력입니다.")
    Integer version,
    LocalDate recordDate,
    Integer mileage,
    RecordType type,
    Long itemId,
    String inspectResult,
    @Size(max = 200, message = "200자 이하로 입력해 주세요.")
    String memo,
    Long mechanicId,
    @Size(max = 200, message = "200자 이하로 입력해 주세요.")
    String reason
) {
}
