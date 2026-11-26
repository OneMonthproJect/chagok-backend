package com.wanted.chagok.domain.cycle.api;

/**
 * 표시 판정에서 기록 한 건이 어떤 종류인지 (04 F-CYC-02). 호출하는 쪽이 정비 기록의 구분·점검 결과를 이 값으로 바꿔 넘긴다.
 * {@code RecordType}·점검 결과는 maintenance 소속이라 cycle이 import할 수 없다.
 */
public enum CycleNoteKind {
    /** 권유했으나 고객이 거절한 기록 (구분 거절). */
    DECLINE,
    /** 점검 기록이고 결과가 주의. */
    INSPECT_CAUTION,
    /** 그 밖의 기록: 교체, 외부, 점검 양호. 표시를 만들지 않고, 더 최근에 있으면 앞선 표시를 지운다. */
    OTHER
}
