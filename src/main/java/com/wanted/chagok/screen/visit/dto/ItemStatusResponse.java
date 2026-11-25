package com.wanted.chagok.screen.visit.dto;

import com.wanted.chagok.domain.cycle.api.BaseRecord;
import com.wanted.chagok.domain.cycle.api.DueReason;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * API 10 응답 (08 10). {@code lastDecline}·{@code lastCaution}은 P0-나머지(#41)에서 더한다.
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
        Integer nextDueMileage) {
    }
}
