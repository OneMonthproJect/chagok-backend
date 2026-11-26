package com.wanted.chagok.domain.maintenance.dto;

import com.wanted.chagok.domain.maintenance.entity.RecordType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * API 12 요청 (08 12번). 요청에 {@code shopId}·담당 필드가 없다 — 정비소는 토큰에서, 담당은 저장한 계정으로 서버가 채운다
 * (08 0.1, D-51). 본문에 {@code shopId}가 와도 읽지 않는다 (X8).
 *
 * <p>여기서는 형식(필수·길이)만 본다. 방문일·주행거리·연식의 범위, 항목, 중복, 차량이 비어 있으면 필수(D-58)는 차량 소유 확인 뒤
 * {@code MaintenanceService}가 한다. 외부·거절·점검 결과·담당({@code mechanicId}) 입력도 같은 곳에서 검증한다.
 *
 * @param visitDate 오늘 이전·오늘
 * @param mileage   방문 주행거리, 정수 0~2,000,000. 교체·점검이 있고 차량의 최근 주행거리가 비어 있으면 필수 (D-58)
 * @param vehicle   차량의 비어 있는 연식을 채울 때만 쓴다. 교체·점검이 있고 차량의 연식이 비어 있으면 필수 (D-58)
 * @param records   1개 이상, 같은 {@code itemId} 중복 불가
 */
public record VisitSaveRequest(
    @NotNull(message = "필수 입력입니다.")
    LocalDate visitDate,
    Integer mileage,
    @Valid
    VehicleInput vehicle,
    Long mechanicId,
    @NotEmpty(message = "기록할 항목을 1개 이상 넣어 주세요.")
    @Valid
    List<@NotNull(message = "필수 입력입니다.") @Valid RecordInput> records
) {

    /** @param modelYear 1980 ~ 현재 연도 + 1. 이미 연식이 있는 차량이면 무시한다 */
    public record VehicleInput(Integer modelYear) {
    }

    /**
     * @param itemId        그 정비소의 쓰는 항목 (X11)
     * @param type          {@code REPLACE}·{@code INSPECT}·{@code EXTERNAL}·{@code DECLINE}
     * @param memo          ~200자
     * @param externalDate  외부면 필수, 오늘 이전. 다른 구분에서 보내면 400
     * @param inspectResult {@code INSPECT}일 때만 {@code GOOD}·{@code CAUTION}
     */
    public record RecordInput(
        @NotNull(message = "필수 입력입니다.")
        Long itemId,
        @NotNull(message = "필수 입력입니다.")
        RecordType type,
        @Size(max = 200, message = "200자 이하로 입력해 주세요.")
        String memo,
        LocalDate externalDate,
        Integer externalMileage,
        String inspectResult
    ) {
    }
}
