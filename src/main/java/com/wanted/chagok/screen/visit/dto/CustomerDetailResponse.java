package com.wanted.chagok.screen.visit.dto;

import java.util.List;

/**
 * API 07 고객 상세 응답 (08 07). {@code phone}은 P0-핵심에서 전체 번호다(D-40). 차량마다 {@code dueCount}·{@code soonCount}는
 * 오늘(서버 {@code Clock}) 기준, 그 정비소가 쓰는 항목 중 도래·임박인 항목의 수이고 API 10을 주행거리 없이 부른 결과와 같은 기준이다.
 *
 * @param vehicles 차량 id 오름차순, 차량이 없으면 빈 목록
 */
public record CustomerDetailResponse(
    long customerId,
    String name,
    String phone,
    boolean consent,
    List<Vehicle> vehicles
) {

    /** {@code modelYear}·{@code latestMileage}는 비어 있을 수 있다(07 3.5). */
    public record Vehicle(
        long vehicleId,
        String plateNumber,
        String model,
        Integer modelYear,
        Integer latestMileage,
        int dueCount,
        int soonCount
    ) {
    }
}
