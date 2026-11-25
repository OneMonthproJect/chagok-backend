package com.wanted.chagok.domain.maintenance.entity;

/**
 * 정비 구분 (0.4, 07 3.7 {@code record_type}). 주기를 다시 시작하는지는 {@link #resetsCycle()} 한 곳에서 정한다
 * (CONVENTIONS 4장): 교체·외부는 true, 점검·거절은 false. 호출하는 쪽이 값을 보고 다시 판단하지 않는다.
 * 입력은 P0-핵심에서 {@code REPLACE}·{@code INSPECT}만, {@code EXTERNAL}·{@code DECLINE}은 P0-나머지부터지만 시드에는 4개가 모두 있다.
 */
public enum RecordType {

    REPLACE(true),
    INSPECT(false),
    EXTERNAL(true),
    DECLINE(false);

    private final boolean resetsCycle;

    RecordType(boolean resetsCycle) {
        this.resetsCycle = resetsCycle;
    }

    /** 이 기록이 그 항목의 주기를 다시 시작하는가. */
    public boolean resetsCycle() {
        return resetsCycle;
    }

    /** 고객이 말한 외부 정비 (source = CUSTOMER_STATEMENT). 방문 횟수·방문일 계산에서 따로 다룬다 (05 POL-45). */
    public boolean isExternal() {
        return this == EXTERNAL;
    }
}
