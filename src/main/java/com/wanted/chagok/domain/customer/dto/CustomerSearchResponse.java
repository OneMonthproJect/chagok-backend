package com.wanted.chagok.domain.customer.dto;

import java.util.List;

/** API 05 응답 (08 05). phone은 P0-핵심에서 전체 번호(마스킹은 F-CUS-06 이슈, P0-나머지). */
public record CustomerSearchResponse(
    List<Item> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {

    public record Item(long customerId, String name, String phone, boolean consent, List<VehicleItem> vehicles) {
    }

    public record VehicleItem(long vehicleId, String plateNumber) {
    }
}
