package com.wanted.chagok.domain.customer.dto;

import java.util.List;

/** API 05 응답 (08 05). {@code phone}은 마스킹한 값이다(08 0.5, F-CUS-07). 상세(07·09)에서만 전체 번호를 준다. */
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
