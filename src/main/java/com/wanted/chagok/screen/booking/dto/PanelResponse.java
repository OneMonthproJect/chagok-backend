package com.wanted.chagok.screen.booking.dto;

import com.wanted.chagok.domain.cycle.api.BaseRecord;
import com.wanted.chagok.domain.cycle.api.DueReason;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.maintenance.api.VisitRef;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API 17 예약 상세 패널 응답 (08 17번). 값만 담는다 — 업무 판단은 각 도메인과 {@code cycle}이 한다.
 *
 * <p>{@code assignee}는 P0-나머지부터라 없다(08 17번 표). {@code recentVisits}는 11번의 방문 모양 그대로다.
 * {@code itemStatuses}는 10번을 주행거리 없이 부른 결과와 같은 값에 {@code highlighted}를 더한 것이고,
 * 순서는 정비 항목 {@code sortOrder} 순이다(D-63 B02).
 */
public record PanelResponse(
    ReservationBlock reservation,
    CustomerBlock customer,
    VehicleBlock vehicle,
    List<VisitRef> recentVisits,
    long totalVisitCount,
    List<ItemStatusBlock> itemStatuses) {

    public record ReservationBlock(
        long reservationId,
        LocalDateTime reservedAt,
        String status,
        boolean needsAction,
        int version,
        Purpose purpose) {
    }

    public record Purpose(List<PurposeItem> items, boolean other, String memo) {
    }

    public record PurposeItem(long itemId, String name) {
    }

    /** phone은 P0-핵심에서 전체 번호다(D-40). consent는 고객 표의 동의 여부 그대로다. */
    public record CustomerBlock(long customerId, String name, String phone, boolean consent) {
    }

    public record VehicleBlock(long vehicleId, String plateNumber, String model, Integer modelYear,
                               Integer latestMileage) {
    }

    public record ItemStatusBlock(
        long itemId,
        String code,
        String name,
        ItemStatus status,
        DueReason dueReason,
        BaseRecord baseRecord,
        LocalDate nextDueDate,
        Integer nextDueMileage,
        boolean highlighted) {
    }
}
