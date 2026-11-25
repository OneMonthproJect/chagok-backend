package com.wanted.chagok.domain.cycle.api;

/**
 * 항목 하나의 점검 주기. {@code service_item}의 {@code cycle_months}·{@code cycle_km} 값이다 (07 3.6).
 * cycle은 표를 모르므로 호출하는 쪽이 값을 꺼내 넘긴다.
 */
public record CycleRule(int cycleMonths, int cycleKm) {

    public CycleRule {
        if (cycleMonths <= 0 || cycleKm <= 0) {
            throw new IllegalArgumentException("주기는 0보다 커야 한다 (07 CHECK > 0): cycleMonths=" + cycleMonths + ", cycleKm=" + cycleKm);
        }
    }
}
