package com.wanted.chagok.domain.reservation.entity;

/**
 * 예약 이력 종류 (07 4.2 {@code reservation_revision.kind}, 08 0.4). V2 CHECK는 세 값을 허용한다.
 */
public enum RevisionKind {
    /** 상태 정정 (API 40, D-53): 이전·이후 상태가 항상 있다. */
    STATUS,
    /** 예약 메모 정정 (API 40, D-53): 이전 메모는 NULL일 수 있어 DB CHECK가 아니라 서버가 지킨다. */
    MEMO,
    /** 담당 재배정 (API 19, D-53): 이전·이후 담당이 항상 있다. */
    ASSIGNEE
}
