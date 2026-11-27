package com.wanted.chagok.screen.visit.dto;

import com.wanted.chagok.domain.cycle.api.BaseRecord;
import com.wanted.chagok.domain.cycle.api.CycleNote;
import com.wanted.chagok.domain.cycle.api.DueReason;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * API 10 응답 (08 10). P0-나머지부터 항목마다 {@code lastDecline}·{@code lastCaution}이 붙는다(F-CYC-02, #110).
 * 두 값은 {@code cycle}이 판정한 값을 그대로 담고 상태를 바꾸지 않으며, 한 항목에 동시에 붙지 않는다. 해당 없으면 null이다.
 *
 * @param asOf         판정 기준일(오늘, Clock)
 * @param inputMileage 요청의 mileage 그대로. 안 주면 null(날짜 기준만)
 */
public record ItemStatusResponse(long vehicleId, LocalDate asOf, Integer inputMileage, List<Item> items) {

    public record Item(
        long itemId,
        String code,
        String name,
        ItemStatus status,
        DueReason dueReason,
        BaseRecord baseRecord,
        LocalDate nextDueDate,
        Integer nextDueMileage,
        CycleNote lastDecline,
        CycleNote lastCaution) {
    }
}
