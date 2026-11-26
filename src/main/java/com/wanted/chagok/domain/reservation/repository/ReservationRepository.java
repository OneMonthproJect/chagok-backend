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

    /**
     * 기간 조회(API 15)의 "내 담당만" 보기: {@link #findInPeriod}와 같고 담당 정비사가 {@code assigneeId}인 예약만.
     * 화면 필터일 뿐이라 정비소 조건({@code shopId})은 그대로 둔다 — 다른 정비소 담당 id를 줘도 결과는 비어 있다 (08 15번).
     */
    @Query("""
        select r from Reservation r
        where r.shopId = :shopId
          and r.assigneeStaffId = :assigneeId
          and r.reservedAt >= :from
          and r.reservedAt < :toExclusive
        order by r.reservedAt asc, r.id asc
        """)
    List<Reservation> findInPeriodByAssignee(@Param("shopId") Long shopId,
                                             @Param("assigneeId") Long assigneeId,
                                             @Param("from") LocalDateTime from,
                                             @Param("toExclusive") LocalDateTime toExclusive);

    /**
     * 월 요약(API 42)용: 기간 안 예약의 예약 일시와 상태 이름만. 상태는 문자열로 읽는다 — {@code IN_PROGRESS}·{@code NO_SHOW}는
     * V2 CHECK는 허용하지만 enum 값은 #33·#36이 더하므로, 엔티티로 읽으면 그 행에서 매핑 오류가 난다. 날짜별 집계는 service가 한다
     * (기간 최대 42일). 취소도 읽는다 — 상태별 건수에 필요하다.
     */
    @Query("""
        select r.reservedAt as reservedAt, cast(r.status as string) as status
        from Reservation r
        where r.shopId = :shopId
          and r.reservedAt >= :from
          and r.reservedAt < :toExclusive
        """)
    List<ReservedStatusRow> findStatusRowsInPeriod(@Param("shopId") Long shopId,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("toExclusive") LocalDateTime toExclusive);

    /** {@link #findStatusRowsInPeriod}의 한 행. */
    interface ReservedStatusRow {

        LocalDateTime getReservedAt();

        String getStatus();
    }
}
