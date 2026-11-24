package com.wanted.chagok.domain.customer.api;

/**
 * 목록 줄 표시용 차량·고객 요약 (예약 목록 API 15 등, backend#26 요청). 연락처는 P0-핵심에서 전체 번호다 (08 0.5).
 * P0-나머지의 마스킹(F-CUS-07)은 이 계약의 값에 customer가 한 곳에서 적용한다.
 */
public record VehicleSummary(
    long vehicleId,
    String plateNumber,
    long customerId,
    String customerName,
    String phone
) {
}
