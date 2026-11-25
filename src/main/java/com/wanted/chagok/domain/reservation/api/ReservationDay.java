package com.wanted.chagok.domain.reservation.api;

import java.util.List;

/**
 * 하루치 예약 (홈, 08 04번). 건수와 목록의 기준이 다르다 — 건수는 취소를 빼고, 목록은 취소를 포함해 상태 라벨로 구분한다(D-63 B05).
 * 이 구분은 reservation이 한 곳에서 정하므로 호출하는 쪽이 목록에서 건수를 다시 세지 않는다.
 *
 * @param countExcludingCancelled 취소를 뺀 건수 ("오늘 예약 N건"). 방문 완료는 포함한다
 * @param reservations            예약 시각 순, 취소 포함
 */
public record ReservationDay(int countExcludingCancelled, List<ReservationSummary> reservations) {
}
