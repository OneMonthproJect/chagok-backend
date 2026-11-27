package com.wanted.chagok.domain.maintenance.repository;

import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecordRevision;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 정비 기록 수정 이력 (07 4.1). 모든 조회에 {@code shopId}가 들어간다 (07 I-3). */
public interface MaintenanceRecordRevisionRepository extends JpaRepository<MaintenanceRecordRevision, Long> {

    /** API 38: 한 기록의 이력을 수정 번호 오름차순으로. */
    List<MaintenanceRecordRevision> findByShopIdAndRecordIdOrderByRevisionNoAsc(long shopId, long recordId);

    /** 그 기록의 지금까지 수정 횟수 (다음 수정 번호 = 이 값 + 1). */
    long countByShopIdAndRecordId(long shopId, long recordId);

    /**
     * API 11: 수정 이력이 있는 기록의 "처음 담당"(첫 수정 전 담당)을 한 번에 (N+1 방지). 결과에 나오는 기록이 곧
     * {@code edited = true}인 기록이고, 방문 묶음의 {@code staffName}은 이 값으로 정한다(08 11: 묶음의 staffName은 바꾸지 않는다).
     */
    @Query("select r.recordId as recordId, r.prevStaffId as staffId from MaintenanceRecordRevision r "
        + "where r.shopId = :shopId and r.recordId in :recordIds and r.revisionNo = 1")
    List<OriginalStaff> findOriginalStaff(@Param("shopId") long shopId, @Param("recordIds") Collection<Long> recordIds);

    /** 기록 하나의 처음 담당. */
    interface OriginalStaff {

        long getRecordId();

        long getStaffId();
    }
}
