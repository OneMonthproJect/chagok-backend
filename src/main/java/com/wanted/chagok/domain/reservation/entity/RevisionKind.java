package com.wanted.chagok.domain.reservation.entity;

/**
 * 예약 이력 종류 (07 4.2 {@code reservation_revision.kind}, 08 0.4). 지금은 재배정만 쓴다.
 * 상태·메모 정정({@code STATUS}·{@code MEMO}, API 40)은 그 이슈(backend#38)에서 더한다 — V2 CHECK는 셋을 모두 허용한다.
 */
public enum RevisionKind {
    /** 담당 재배정 (API 19, D-53). */
    ASSIGNEE
}
