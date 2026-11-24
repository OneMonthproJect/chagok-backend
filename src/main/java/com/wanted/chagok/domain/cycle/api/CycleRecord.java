package com.wanted.chagok.domain.cycle.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 그 항목의 정비 기록 한 건 (계산 재료). 호출하는 쪽이 정비 기록을 이 값으로 바꿔 넘긴다.
 *
 * <p>무엇이 주기를 다시 시작하는지는 cycle이 정하지 않는다. {@code RecordType.resetsCycle()} 한 곳에서 정하고
 * (CONVENTIONS 4장), 호출하는 쪽이 그 결과를 {@code resetsCycle}에 담는다. 교체·외부는 true, 점검·거절은 false다.
 *
 * @param date        기록일. 외부는 고객이 말한 정비일, 나머지는 방문일
 * @param mileage     그때의 주행거리. 없으면 null (외부 기록 등)
 * @param resetsCycle 이 기록이 주기를 다시 시작하면 true
 * @param createdAt   저장 시각. 같은 날 기록끼리 나중에 저장한 것을 고르는 기준
 * @param id          기록 id. {@code createdAt}도 같을 때 큰 것이 나중
 * @param type        응답에 그대로 보여 줄 구분 표시값 (예: {@code REPLACE}). {@code RecordType}은 maintenance 소속이라
 *                    cycle이 import할 수 없어 문자열로 받는다. F-CYC-01 계산은 이 값을 해석하지 않고
 *                    {@code resetsCycle}만 본다(F-CYC-02에서 거절·점검 판정이 필요하면 그때 확장한다)
 */
public record CycleRecord(
        LocalDate date,
        Integer mileage,
        boolean resetsCycle,
        LocalDateTime createdAt,
        long id,
        String type) {

    public CycleRecord {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(type, "type");
    }
}
