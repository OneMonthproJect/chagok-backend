package com.wanted.chagok.domain.maintenance.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * maintenance가 다른 곳에 주는 읽기 계약 (screen/visit API 10, screen/target API 14, screen/booking API 17이 쓴다).
 * 구현은 {@code MaintenanceService}. 기존 메서드는 바꾸지 않고 메서드를 더한다 (CONVENTIONS 3장).
 * 모든 메서드는 {@code shopId}의 데이터만 본다(I-3). 차량이 우리 정비소 것인지는 먼저
 * {@code customer.api.CustomerReader.getOwnedVehicle}로 확인한다(이 계약은 없는 차량에 빈 결과를 돌려줄 뿐 404를 던지지 않는다).
 */
public interface MaintenanceReader {

    /**
     * 차량들의 정비 기록 전부(점검·거절·외부 포함), 차량별로 묶어서. 항목 상태 계산 재료다.
     * 한 번의 조회로 가져온다(차량마다 부르지 않는다, 대상 목록 14의 N+1 방지).
     * 기록이 없는 차량도 키는 있고 값은 빈 목록이다. 목록은 기록일·저장 시각·id 오름차순.
     */
    Map<Long, List<MaintenanceRecordRef>> findRecordsByVehicle(long shopId, Collection<Long> vehicleIds);

    /** 그 차량의 최근 방문 최대 {@code limit}회, 최신순 (예약 상세 17은 2회, D-54). 기록이 없으면 빈 목록. */
    List<VisitRef> findRecentVisits(long shopId, long vehicleId, int limit);

    /**
     * 그 차량의 전체 방문 횟수: 서로 다른 {@code visit_key} 수, <b>외부 기록만 있는 방문은 제외</b> (05 POL-45).
     * 예약 상세 17의 {@code totalVisitCount}.
     */
    long countVisits(long shopId, long vehicleId);
}
