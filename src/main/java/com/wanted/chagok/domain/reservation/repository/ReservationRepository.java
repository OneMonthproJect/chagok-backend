package com.wanted.chagok.domain.reservation.repository;

import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 예약 조회. 정비소 데이터이므로 모든 조회에 {@code shopId} 조건이 들어간다 (CONVENTIONS 4장, 07 I-3). */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** {@code getOwned}의 바탕. 없거나 다른 정비소 것이면 비어 있다(둘을 구분하지 않는다, D-12). */
    Optional<Reservation> findByIdAndShopId(Long id, Long shopId);


    /**
     * 차량들의 유효 예약 후보: 상태가 {@code status}이고 예약 일시가 {@code from} 이후. 이른 순서로 돌려준다.
     * 차량마다 가장 이른 것을 고르는 일은 service가 한다.
     */
    @Query("""
        select r from Reservation r
        where r.shopId = :shopId
          and r.vehicleId in :vehicleIds
          and r.status = :status
          and r.reservedAt >= :from
        order by r.reservedAt asc, r.id asc
        """)
    List<Reservation> findByVehiclesAndStatusFrom(@Param("shopId") Long shopId,
                                                  @Param("vehicleIds") Collection<Long> vehicleIds,
                                                  @Param("status") ReservationStatus status,
                                                  @Param("from") LocalDateTime from);

    /**
     * 차량들의 {@code status} 예약을 날짜와 무관하게 이른 순서로 돌려준다. 진행중({@code IN_PROGRESS})은 날짜와 상관없이
     * 유효 예약이라서 쓴다 (D-52, R23).
     */
    @Query("""
        select r from Reservation r
        where r.shopId = :shopId
          and r.vehicleId in :vehicleIds
          and r.status = :status
        order by r.reservedAt asc, r.id asc
        """)
    List<Reservation> findByVehiclesAndStatus(@Param("shopId") Long shopId,
                                              @Param("vehicleIds") Collection<Long> vehicleIds,
                                              @Param("status") ReservationStatus status);

    /**
     * 기간 조회(API 15): 예약 일시가 {@code from} 이상 {@code toExclusive} 미만. 취소·노쇼도 포함하고 시각 순
     * (같은 시각은 id 순)으로 돌려준다. 쪽 나누기는 없다(최대 42일, 08 15번).
     */
    @Query("""
        select r from Reservation r
        where r.shopId = :shopId
          and r.reservedAt >= :from
          and r.reservedAt < :toExclusive
        order by r.reservedAt asc, r.id asc
        """)
    List<Reservation> findInPeriod(@Param("shopId") Long shopId,
                                   @Param("from") LocalDateTime from,
                                   @Param("toExclusive") LocalDateTime toExclusive);
}
