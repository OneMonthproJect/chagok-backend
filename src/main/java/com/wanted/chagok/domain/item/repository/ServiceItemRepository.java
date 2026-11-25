package com.wanted.chagok.domain.item.repository;

import com.wanted.chagok.domain.item.entity.ServiceItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 모든 조회에 {@code shopId}가 들어간다 (08 0.1, 07 I-3). */
public interface ServiceItemRepository extends JpaRepository<ServiceItem, Long> {

    List<ServiceItem> findByShopIdAndActiveTrueOrderBySortOrderAscIdAsc(long shopId);

    /** 비활성 포함. 없거나 다른 정비소 항목은 결과에 없다. */
    List<ServiceItem> findByShopIdAndIdIn(long shopId, Collection<Long> ids);
}
