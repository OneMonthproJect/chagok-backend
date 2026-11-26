package com.wanted.chagok.domain.reservation.entity;

/**
 * 예약 상태 (08 0.4). V2의 {@code ck_reservation_status} CHECK가 허용하는 값과 같아야 한다 (07 6.3).
 *
 * <p>{@code IN_PROGRESS}(진행중, 방문 접수 39, D-52)는 V2(backend#31)로 허용됐다. {@code NO_SHOW}(노쇼)는 노쇼 이슈(backend#33)에서 더한다.
 */
public enum ReservationStatus {
    /** 예약. 시각이 지났어도 상태는 그대로이고 화면이 "처리 필요"로 표시한다 (D-23). */
    RESERVED,
    /** 진행중: 방문 접수(39)로 차량이 도착한 예약. 유효 예약이며 날짜와 무관하다 (D-52). */
    IN_PROGRESS,
    /** 방문 완료. */
    COMPLETED,
    /** 취소. */
    CANCELED
}
