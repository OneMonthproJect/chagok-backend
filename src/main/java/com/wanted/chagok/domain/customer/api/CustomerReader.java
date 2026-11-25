package com.wanted.chagok.domain.customer.api;

import java.util.Collection;
import java.util.List;
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

    /**
     * 안내 수신에 동의한({@code consent = true}) 고객의 차량 전체를 한 번에 돌려준다 (API 14 재점검 대상 목록, 04 F-TGT-01).
     * 미동의 고객의 차량은 나오지 않고, 동의 여부 판단은 이 메서드 한 곳이다(호출하는 쪽이 {@code consent}를 다시 걸러내지 않는다).
     * 차량은 id 오름차순이고 연락처는 P0-핵심에서 전체 번호다 (D-40). 그 정비소의 차량만 나온다(다른 정비소는 섞이지 않는다).
     *
     * <p>LIMIT: 쪽 나누기 없이 전부 돌려준다(API 14도 쪽 나누기가 없다). 정비소당 차량 수백 대 이하라고 가정한다.
     * 넘으면 항목 상태 계산 쪽과 함께 기간·쪽 나누기를 다시 정한다.
     *
     * @param shopId 로그인한 계정의 정비소 (요청 값이 아니다, 07 I-3)
     */
    List<VehicleSummary> findConsentedVehicles(long shopId);
}
