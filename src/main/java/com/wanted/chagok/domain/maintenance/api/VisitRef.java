package com.wanted.chagok.domain.maintenance.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 방문 한 번(같은 {@code visit_key}의 기록 묶음). 08 11번 {@code items[]} 한 줄과 같은 모양이라
 * 예약 상세 패널 17의 {@code recentVisits}에 그대로 옮긴다.
 *
 * @param visitKey  UUID 36자 (07 3.7)
 * @param visitDate 그 묶음에서 외부가 아닌 기록의 날짜(외부만 있으면 그 날짜)
 * @param mileage   방문 주행거리. 비어 있을 수 있음
 * @param staffName 기록 담당 정비사 이름
 */
public record VisitRef(
    String visitKey,
    LocalDate visitDate,
    Integer mileage,
    String staffName,
    List<VisitRecord> records
) {

    /**
     * 08 11번 {@code records[]}: {@code { recordId, itemId, itemName, type, date, memo, version, edited, staff }}.
     * {@code version}·{@code edited}·{@code staff}는 F-MNT-04부터 모든 기록에 붙는다.
     *
     * @param version 수정 확인용 번호 (저장 때 0, 수정마다 +1)
     * @param edited  수정 이력(38)이 한 건이라도 있으면 true
     * @param staff   그 기록의 담당 정비사
     */
    public record VisitRecord(
        long recordId,
        long itemId,
        String itemName,
        String type,
        LocalDate date,
        String memo,
        int version,
        boolean edited,
        RecordStaff staff
    ) {
    }

    /** 기록의 담당 정비사 {@code { staffId, name }}. {@code name}은 그 계정의 현재 이름이다. */
    public record RecordStaff(long staffId, String name) {
    }
}
