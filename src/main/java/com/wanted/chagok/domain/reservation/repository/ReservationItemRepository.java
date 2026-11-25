package com.wanted.chagok.domain.reservation.repository;

import com.wanted.chagok.domain.reservation.entity.ReservationItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 목적 항목. 정비소 데이터이므로 조회에는 {@code shopId} 조건을 함께 넣는다 (07 I-3).
 * 교체 메서드는 API 19(변경) 이슈에서 더한다.
 */
public interface ReservationItemRepository extends JpaRepository<ReservationItem, Long> {

    /** 예약 목록(API 15)의 {@code purposeSummary} 재료. 예약마다 부르지 않고 한 번에 가져온다(N+1 방지). */
    List<ReservationItem> findByShopIdAndReservationIdIn(Long shopId, Collection<Long> reservationIds);
}
