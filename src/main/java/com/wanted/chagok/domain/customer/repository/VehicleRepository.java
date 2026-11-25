package com.wanted.chagok.domain.customer.repository;

import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.customer.entity.Vehicle;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 모든 조회에 {@code shopId}가 들어간다 (08 0.1, 07 I-3). */
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByIdAndShopId(Long id, long shopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Vehicle> findForUpdateByIdAndShopId(Long id, long shopId);

    List<Vehicle> findByShopIdAndCustomerIdInOrderByIdAsc(long shopId, Collection<Long> customerIds);

    /** 차량 여러 대의 차량·고객 요약을 한 번에 (CustomerReader.findVehicleSummaries). */
    @Query("""
        select new com.wanted.chagok.domain.customer.api.VehicleSummary(v.id, v.plateNumber, c.id, c.name, c.phone, c.consent)
        from Vehicle v join Customer c on c.shopId = v.shopId and c.id = v.customerId
        where v.shopId = :shopId and v.id in :vehicleIds
        """)
    List<VehicleSummary> findSummaries(@Param("shopId") long shopId, @Param("vehicleIds") Collection<Long> vehicleIds);

    /** 안내 수신에 동의한 고객의 차량 전체, id 오름차순 (CustomerReader.findConsentedVehicles, API 14). */
    @Query("""
        select new com.wanted.chagok.domain.customer.api.VehicleSummary(v.id, v.plateNumber, c.id, c.name, c.phone, c.consent)
        from Vehicle v join Customer c on c.shopId = v.shopId and c.id = v.customerId
        where v.shopId = :shopId and c.consent = true
        order by v.id
        """)
    List<VehicleSummary> findConsentedSummaries(@Param("shopId") long shopId);
}
