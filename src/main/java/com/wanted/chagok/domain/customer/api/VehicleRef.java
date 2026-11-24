package com.wanted.chagok.domain.customer.api;

/**
 * 우리 정비소 차량 한 대의 참조 (Entity가 아니라 값). 다른 도메인은 이 값으로만 차량을 안다 (CONVENTIONS 3장).
 *
 * <p>{@code modelYear}·{@code latestMileage}는 비어 있을 수 있다 (07 3.5, D-58: 정비 시작 때 필수).
 */
public record VehicleRef(
    long vehicleId,
    long customerId,
    String plateNumber,
    String model,
    Integer modelYear,
    Integer latestMileage
) {
}
