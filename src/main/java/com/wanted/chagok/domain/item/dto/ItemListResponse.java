package com.wanted.chagok.domain.item.dto;

import com.wanted.chagok.domain.item.entity.ServiceItem;

/** API 13 응답 {@code data[]}의 한 줄 (08 13). {@code code}는 0.4 항목 값, 주기는 07 3.6(0보다 큼). */
public record ItemListResponse(
    long itemId,
    String code,
    String name,
    int cycleMonths,
    int cycleKm,
    boolean active,
    int sortOrder
) {

    public static ItemListResponse from(ServiceItem item) {
        return new ItemListResponse(item.getId(), item.getCode(), item.getName(), item.getCycleMonths().intValue(),
            item.getCycleKm(), item.isActive(), item.getSortOrder().intValue());
    }
}
