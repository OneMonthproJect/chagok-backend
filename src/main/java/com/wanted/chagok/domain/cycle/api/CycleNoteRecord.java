package com.wanted.chagok.domain.cycle.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 표시 판정의 재료: 그 차량·그 항목의 정비 기록 한 건 (04 F-CYC-02). {@link CycleRecord}와 같은 기록을 가리키지만
 * 거절·점검 주의·메모 정보가 필요해서 따로 둔다(기존 계약은 바꾸지 않는다).
 *
 * <p>무엇이 주기를 다시 시작하는지는 cycle이 정하지 않는다. {@code RecordType.resetsCycle()}가 정하고(CONVENTIONS 4장),
 * 호출하는 쪽이 그 결과를 {@code resetsCycle}에 담는다. 교체·외부는 true다.
 *
 * @param date        기록일. 외부는 고객이 말한 정비일, 나머지는 방문일
 * @param createdAt   저장 시각. 같은 날 기록끼리 나중에 저장한 것을 고르는 기준
 * @param id          기록 id. {@code createdAt}도 같을 때 큰 것이 나중
 * @param resetsCycle 이 기록이 주기를 다시 시작하면 true (교체·외부)
 * @param kind        표시 판정에서의 종류
 * @param memo        기록 메모. 없으면 null
 */
public record CycleNoteRecord(
        LocalDate date,
        LocalDateTime createdAt,
        long id,
        boolean resetsCycle,
        CycleNoteKind kind,
        String memo) {

    public CycleNoteRecord {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(kind, "kind");
        if (resetsCycle && kind != CycleNoteKind.OTHER) {
            throw new IllegalArgumentException("주기를 다시 시작하는 기록(교체·외부)은 거절·점검 주의가 아니다: kind=" + kind);
        }
    }
}
