package com.wanted.chagok.domain.customer.api;

/**
 * 목록 줄 표시용 차량·고객 요약 (예약 목록 API 15 등, backend#26 요청). 연락처는 P0-핵심에서 전체 번호다 (08 0.5).
 * P0-나머지의 마스킹(F-CUS-07)은 이 계약의 값에 customer가 한 곳에서 적용한다.
 *
 * <p>{@code consent}는 고객 표의 동의 여부를 그대로 옮긴 값이다(08 17번 예약 상세 패널의 {@code customer.consent}, backend#30 요청).
 */
public record VehicleSummary(
    long vehicleId,
    String plateNumber,
    long customerId,
    String customerName,
    String phone,
    boolean consent
) {
}
