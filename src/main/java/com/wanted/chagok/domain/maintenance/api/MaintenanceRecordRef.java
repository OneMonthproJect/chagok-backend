package com.wanted.chagok.domain.maintenance.api;

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
    String memo
) {
}
