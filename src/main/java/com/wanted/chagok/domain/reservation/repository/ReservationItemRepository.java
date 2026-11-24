package com.wanted.chagok.domain.reservation.repository;

import com.wanted.chagok.domain.reservation.entity.ReservationItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 목적 항목. 지금은 기본 메서드만 둔다. 조회·교체 메서드는 쓰는 이슈(API 15·16 등록, API 19 변경)에서 더한다
 * (조회에는 {@code shopId} 조건을 함께 넣는다).
 */
public interface ReservationItemRepository extends JpaRepository<ReservationItem, Long> {
}
