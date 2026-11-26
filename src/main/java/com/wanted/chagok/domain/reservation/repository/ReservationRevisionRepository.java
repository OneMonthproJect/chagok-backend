package com.wanted.chagok.domain.reservation.repository;

import com.wanted.chagok.domain.reservation.entity.ReservationRevision;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 이력 저장 (07 4.2). 지금은 재배정(API 19)이 남기는 쓰기만 한다. 조회(API 41)는 정비소 조건({@code shopId})이 들어간
 * 쿼리로 그 이슈에서 더한다 (CONVENTIONS 4장, 07 I-3).
 */
public interface ReservationRevisionRepository extends JpaRepository<ReservationRevision, Long> {
}
