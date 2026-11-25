package com.wanted.chagok.domain.reservation.entity;

/**
 * 예약 상태 (08 0.4). P0-핵심 값만 둔다 — V1의 {@code ck_reservation_status} CHECK가 이 셋만 허용한다 (07 6.3 V1-8).
 *
 * <p>{@code IN_PROGRESS}(진행중)·{@code NO_SHOW}(노쇼)는 P0-나머지에서 V2 마이그레이션(backend#31)과 함께 더한다.
 */
public enum ReservationStatus {
    /** 예약. 시각이 지났어도 상태는 그대로이고 화면이 "처리 필요"로 표시한다 (D-23). */
    RESERVED,
    /** 방문 완료. */
    COMPLETED,
    /** 취소. */
    CANCELED
}
