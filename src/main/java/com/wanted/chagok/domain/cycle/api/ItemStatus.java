package com.wanted.chagok.domain.cycle.api;

/**
 * 정비 항목의 점검 주기 상태 (04 F-CYC-01, 08 0장 {@code status}). 저장하지 않고 조회 때 계산한다 (CONVENTIONS 1장 6번).
 * 이름은 08의 enum 값과 글자까지 같다.
 */
public enum ItemStatus {
    /** 미확인: 주기를 시작한 기록(교체·외부)이 없다. */
    UNKNOWN,
    /** 여유. */
    AHEAD,
    /** 임박: 도래가 아니고, 오늘 + 1개월 ≥ 다음 정비일. */
    SOON,
    /** 도래: 오늘 ≥ 다음 정비일, 또는 방문 주행거리 ≥ 기준 km + 주기 km. */
    DUE
}
