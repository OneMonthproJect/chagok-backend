package com.wanted.chagok.screen.target.dto;

import com.wanted.chagok.domain.cycle.api.CycleNote;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API 14 재점검 대상 목록 응답 (08 14번, 04 F-TGT-01). 값만 담는다 — 업무 판단은 각 도메인과 {@code cycle}이 한다.
 *
 * <p>P0-나머지부터 {@code targetItems[]}마다 {@code lastDecline}·{@code lastCaution}이 붙는다(10번과 같은 모양·조건, F-CYC-02, #110).
 * 연락처는 P0-핵심에서 전체 번호다(D-40, P0-나머지부터 마스킹).
 * {@code targetItems}는 도래·임박 항목만이고 항목 {@code sortOrder} 순이다.
 *
 * @param asOf  판정 기준일(오늘, Asia/Seoul)
 * @param items 고객·차량별 한 줄. 대상이 없으면 빈 목록
 */
public record TargetListResponse(LocalDate asOf, List<Item> items) {

    /**
     * @param activeReservation 그 차량의 유효 예약("예약 있음"). 없으면 null
     */
    public record Item(
        long customerId,
        String customerName,
        String phone,
        long vehicleId,
        String plateNumber,
        List<TargetItem> targetItems,
        ActiveReservation activeReservation) {
    }

    /**
     * @param status   {@code DUE}(도래) 또는 {@code SOON}(임박)
     * @param baseDate 계산에 쓴 기준 기록일(10번 {@code baseRecord.date})
     * @param lastDecline 지난 방문 거절 {@code { date, memo }}. 해당 없으면 null (상태를 바꾸지 않는다)
     * @param lastCaution 지난 점검 주의 {@code { date, memo }}. 해당 없으면 null (상태를 바꾸지 않는다)
     */
    public record TargetItem(long itemId, String name, ItemStatus status, LocalDate nextDueDate, LocalDate baseDate,
                             CycleNote lastDecline, CycleNote lastCaution) {
    }

    public record ActiveReservation(long reservationId, LocalDateTime reservedAt) {
    }
}
