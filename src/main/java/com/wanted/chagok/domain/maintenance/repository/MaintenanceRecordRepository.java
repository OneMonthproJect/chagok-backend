package com.wanted.chagok.domain.maintenance.repository;

import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecord;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 모든 조회에 {@code shopId}가 들어간다 (08 0.1, 07 I-3). */
public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {

    /** 한 차량의 기록 전부 (API 11 방문 묶음의 재료). */
    List<MaintenanceRecord> findByShopIdAndVehicleId(long shopId, long vehicleId);

    /** 차량 여러 대의 기록을 한 번에 (MaintenanceReader.findRecordsByVehicle, N+1 방지). */
    List<MaintenanceRecord> findByShopIdAndVehicleIdIn(long shopId, Collection<Long> vehicleIds);
}
