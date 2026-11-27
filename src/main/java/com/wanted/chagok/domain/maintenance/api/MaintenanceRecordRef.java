package com.wanted.chagok.domain.maintenance.api;

import com.wanted.chagok.domain.maintenance.entity.RecordType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정비 기록 한 건 (Entity가 아니라 값). 항목 상태 계산({@code CycleRecord})의 재료라서 필드가 그것과 1:1로 이어진다:
 * 호출하는 쪽이 {@code new CycleRecord(date, mileage, resetsCycle, createdAt, recordId, type)}로 옮기기만 하면 된다.
 *
 * @param type        0.4 정비 구분 표시값 ({@code REPLACE}·{@code INSPECT}·{@code EXTERNAL}·{@code DECLINE}).
 *                    {@code RecordType}은 maintenance 소속이라 문자열로 내보낸다 (cycle 계약과 같은 방식)
 * @param resetsCycle 이 기록이 주기를 다시 시작하는가. {@code RecordType.resetsCycle()} 한 곳의 결과이며
 *                    호출하는 쪽이 {@code type}으로 다시 판단하지 않는다 (CONVENTIONS 4장)
 * @param date        기록일. 외부는 고객이 말한 정비일, 나머지는 방문일
 * @param mileage     그때의 주행거리. 없으면 null
 * @param createdAt   저장 시각 (같은 날 기록 사이의 선후 기준)
 * @param memo        메모. 없으면 null
 * @param inspectResult 점검 결과 {@code GOOD}·{@code CAUTION} (08 0.4). 점검({@code INSPECT})이고 결과를 남겼을 때만 값이 있고
 *                    그 밖은 null (07 3.7 CHECK). 거절·주의 꼬리표(04 F-CYC-02) 판정의 재료다
 */
public record MaintenanceRecordRef(
    long recordId,
    long vehicleId,
    long itemId,
    LocalDate date,
    Integer mileage,
    String type,
    boolean resetsCycle,
    LocalDateTime createdAt,
    String memo,
    String inspectResult
) {

    /**
     * 거절 기록인가. {@code type}의 값 이름은 maintenance가 아는 것이라 호출하는 쪽이 문자열을 다시 비교하지 않고 이 메서드를 쓴다
     * (거절·주의 꼬리표 판정 재료를 만들 때, screen 세 곳이 같은 문자열을 따로 갖지 않게 한다).
     */
    public boolean isDecline() {
        return RecordType.DECLINE.name().equals(type);
    }

    /** 점검 기록이고 결과가 주의인가 (거절·주의 꼬리표의 "지난 점검 주의" 재료). */
    public boolean isInspectCaution() {
        return RecordType.INSPECT.name().equals(type) && "CAUTION".equals(inspectResult);
    }
}
