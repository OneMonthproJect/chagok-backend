package com.wanted.chagok.domain.customer.dto;

import com.wanted.chagok.domain.customer.entity.Customer;
import com.wanted.chagok.domain.customer.entity.Vehicle;

/**
 * API 09 응답 (08 09). {@code model}은 값이 있고(D-58), {@code modelYear}·{@code latestMileage}는 비어 있을 수 있다.
 * {@code version}은 차량 수정(23) 요청에 그대로 보낸다. {@code customer.phone}은 전체 번호다(D-40: 마스킹은 목록만, P0-나머지).
 */
public record VehicleDetailResponse(
    long vehicleId,
    String plateNumber,
    String model,
    Integer modelYear,
    Integer latestMileage,
    int version,
    CustomerInfo customer
) {

    public record CustomerInfo(long customerId, String name, String phone, boolean consent) {
    }

    public static VehicleDetailResponse of(Vehicle vehicle, Customer customer) {
        return new VehicleDetailResponse(vehicle.getId(), vehicle.getPlateNumber(), vehicle.getModel(),
            vehicle.getModelYear(), vehicle.getLatestMileage(), vehicle.getVersion(),
            new CustomerInfo(customer.getId(), customer.getName(), customer.getPhone(), customer.isConsent()));
    }
}
