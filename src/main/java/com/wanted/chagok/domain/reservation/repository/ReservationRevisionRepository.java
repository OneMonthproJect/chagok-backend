package com.wanted.chagok.domain.reservation.repository;

import com.wanted.chagok.domain.reservation.entity.ReservationRevision;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 이력 (07 4.2). 재배정(API 19)·정정(API 40)이 남기는 쓰기와 이력 조회(API 41)를 한다. 정비소 데이터이므로 조회에는
 * {@code shopId} 조건이 들어간다 (CONVENTIONS 4장, 07 I-3).
 */
public interface ReservationRevisionRepository extends JpaRepository<ReservationRevision, Long> {

    /** API 41: 한 예약의 이력을 {@code created_at} 오름차순, 같으면 id 순으로 (07 4.2, 08 41번). */
    List<ReservationRevision> findByShopIdAndReservationIdOrderByCreatedAtAscIdAsc(Long shopId, Long reservationId);
}
