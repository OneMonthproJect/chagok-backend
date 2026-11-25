package com.wanted.chagok.domain.customer.api;

import java.util.Collection;
import java.util.Map;

/**
 * customer가 다른 도메인에 주는 읽기 계약 (maintenance 7-2, reservation 11-1, screen이 쓴다).
 * 구현은 {@code CustomerService}. 기존 메서드는 바꾸지 않고 메서드를 더한다 (CONVENTIONS 3장).
 */
public interface CustomerReader {

    /**
     * 차량이 우리 정비소 것인지 확인하고 돌려준다. 없거나 다른 정비소 차량이면 404 {@code NOT_FOUND} (08 X4, D-12).
     * 차량 소속 확인은 이 메서드 한 곳이다.
     */
    VehicleRef getOwnedVehicle(long shopId, long vehicleId);

    /**
     * 차량 여러 대의 차량·고객 요약을 한 번에 조회한다 (건별 호출 금지, N+1 방지).
     * 없거나 다른 정비소 차량은 결과에 없다(오류 아님). 입력이 비어 있으면 빈 Map.
     */
    Map<Long, VehicleSummary> findVehicleSummaries(long shopId, Collection<Long> vehicleIds);

    /**
     * 고객이 우리 정비소 것인지 확인하고 고객 정보와 차량 목록을 한 번에 돌려준다 (API 07 고객 상세, screen/visit이 쓴다).
     * 없거나 다른 정비소 고객이면 404 {@code NOT_FOUND} (08 X1, D-12). 차량은 id 오름차순이고 차량이 없으면 빈 목록이다.
     * 연락처는 P0-핵심에서 전체 번호다 (D-40). screen이 고객 소속을 확인하는 진입점은 이 메서드다.
     */
    CustomerRef getOwnedCustomer(long shopId, long customerId);
}
