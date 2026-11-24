package com.wanted.chagok.domain.cycle.api;

/**
 * 도래가 된 이유 (08 10번 {@code dueReason}). 상태가 {@link ItemStatus#DUE}일 때만 값이 있다.
 * 날짜와 km가 모두 해당하면 {@link #DATE}다.
 */
public enum DueReason {
    /** 오늘이 다음 정비일이거나 지났다. */
    DATE,
    /** 방문 주행거리가 기준 km + 주기 km 이상이다. */
    KM
}
