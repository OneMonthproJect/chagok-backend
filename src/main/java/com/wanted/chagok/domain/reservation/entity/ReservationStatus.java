package com.wanted.chagok.domain.reservation.entity;

/**
 * 예약 상태 (08 0.4). V2의 {@code ck_reservation_status} CHECK가 허용하는 다섯 값이다 (07 3.8, backend#31).
 *
 * <p>{@code IN_PROGRESS}(진행중)로 가는 길은 방문 접수(API 39)이고 이 값을 만드는 코드는 그 이슈(backend#36)에서 더한다.
 */
public enum ReservationStatus {
    /** 예약. 시각이 지났어도 상태는 그대로이고 화면이 "처리 필요"로 표시한다 (D-23). */
    RESERVED,
    /** 진행중 — 방문 접수 뒤 (D-52). */
    IN_PROGRESS,
    /** 방문 완료. */
    COMPLETED,
    /** 취소. */
    CANCELED,
    /** 노쇼 — 예약 시각이 지난 {@code RESERVED}만 (08 21번, R6). */
    NO_SHOW
}
