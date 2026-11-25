package com.wanted.chagok.domain.reservation.repository;

import com.wanted.chagok.domain.reservation.entity.ReservationItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 예약 목적 항목. 정비소 데이터이므로 조회에는 {@code shopId} 조건을 함께 넣는다 (07 I-3).
 */
public interface ReservationItemRepository extends JpaRepository<ReservationItem, Long> {

    /** 예약 목록(API 15)의 {@code purposeSummary} 재료. 예약마다 부르지 않고 한 번에 가져온다(N+1 방지). */
    List<ReservationItem> findByShopIdAndReservationIdIn(Long shopId, Collection<Long> reservationIds);

    /**
     * 예약 목적 항목 전체를 지운다(변경 API 19는 "지운 뒤 다시 넣는다", 07 3.9). 벌크 삭제라 바로 실행되어, 같은 항목을 다시 넣을 때
     * 유일 제약(예약+항목)과 부딪히지 않는다.
     */
    @Modifying
    @Query("delete from ReservationItem i where i.shopId = :shopId and i.reservationId = :reservationId")
    int deleteAllOf(@Param("shopId") Long shopId, @Param("reservationId") Long reservationId);
}
