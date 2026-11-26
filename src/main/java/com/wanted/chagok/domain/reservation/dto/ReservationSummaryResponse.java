package com.wanted.chagok.domain.reservation.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * API 42 월 요약 응답 (08 7.1 42, F-RSV-05). 홈 월 캘린더의 날짜 칸이 쓴다.
 *
 * @param from  요청 기간 그대로
 * @param to    요청 기간 그대로
 * @param items 예약이 한 건이라도 있는 날짜만, {@code date} 오름차순. 없는 날짜는 0건으로 본다
 */
public record ReservationSummaryResponse(LocalDate from, LocalDate to, List<Item> items) {

    /**
     * @param total      취소만 제외한 건수 (예약·진행중·완료·노쇼). 진행중·노쇼를 포함한다 (D-63 B05)
     * @param reserved   예약
     * @param inProgress 진행중
     * @param completed  방문 완료
     * @param canceled   취소
     * @param noShow     노쇼
     */
    public record Item(
            LocalDate date,
            int total,
            int reserved,
            int inProgress,
            int completed,
            int canceled,
            int noShow) {
    }
}
