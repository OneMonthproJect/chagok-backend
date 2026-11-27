package com.wanted.chagok.domain.maintenance.repository;

import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecord;
import java.util.Collection;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** 모든 조회에 {@code shopId}가 들어간다 (08 0.1, 07 I-3). */
public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {

    /** 한 차량의 기록 전부 (API 11 방문 묶음의 재료). */
    List<MaintenanceRecord> findByShopIdAndVehicleId(long shopId, long vehicleId);

    /** 차량 여러 대의 기록을 한 번에 (MaintenanceReader.findRecordsByVehicle, N+1 방지). */
    List<MaintenanceRecord> findByShopIdAndVehicleIdIn(long shopId, Collection<Long> vehicleIds);

    /** API 37·38: 정비소 소유 기록 한 건. 없거나 남의 정비소 기록이면 비어 있다(404, X14). */
    Optional<MaintenanceRecord> findByShopIdAndId(long shopId, long id);

    /**
     * API 37: 같은 방문의 기록 행을 모두 잠그고(SELECT ... FOR UPDATE, id 순서로 잠가 교착을 막는다) 최신 값으로 읽는다.
     * 같은 방문의 두 기록을 동시에 같은 항목으로 바꾸는 요청이 둘 다 중복 검사를 통과하지 못하게 한다 (H7, DB에는 이 중복을 막는 제약이 없다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<MaintenanceRecord> findByShopIdAndVisitKeyOrderByIdAsc(long shopId, String visitKey);
}
