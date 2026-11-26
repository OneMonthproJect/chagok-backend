package com.wanted.chagok.domain.customer.dto;

import com.wanted.chagok.domain.customer.entity.Vehicle;

/** API 23 응답 (08 23): 바뀐 뒤의 값과 바뀐 {@code version}. */
public record VehicleUpdateResponse(
    long vehicleId,
    String plateNumber,
    String model,
    Integer modelYear,
    Integer latestMileage,
    int version
) {

    public static VehicleUpdateResponse of(Vehicle vehicle) {
        return new VehicleUpdateResponse(vehicle.getId(), vehicle.getPlateNumber(), vehicle.getModel(),
            vehicle.getModelYear(), vehicle.getLatestMileage(), vehicle.getVersion());
    }
}
