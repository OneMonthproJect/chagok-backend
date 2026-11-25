package com.wanted.chagok.domain.item.service;

import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.item.entity.ServiceItem;
import com.wanted.chagok.domain.item.repository.ServiceItemRepository;
import com.wanted.chagok.global.error.BusinessException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ItemReader} 구현 (F-MNT-01 항목). 다른 도메인은 이 클래스가 아니라 {@code item.api}만 본다.
 * 모든 조회는 {@code shopId}와 함께 한다(I-3). API 13(항목 목록)은 이어지는 PR에서 이 클래스에 더한다.
 */
@Service
@Transactional(readOnly = true)
public class ItemService implements ItemReader {

    private static final String NOT_USABLE = "사용할 수 없는 항목입니다.";

    private final ServiceItemRepository items;

    public ItemService(ServiceItemRepository items) {
        this.items = items;
    }

    @Override
    public List<ItemRef> findActiveItems(long shopId) {
        return items.findByShopIdAndActiveTrueOrderBySortOrderAscIdAsc(shopId).stream()
            .map(ItemService::toRef)
            .toList();
    }

    @Override
    public Map<Long, ItemRef> findItems(long shopId, Collection<Long> itemIds) {
        Set<Long> ids = distinctIds(itemIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return items.findByShopIdAndIdIn(shopId, ids).stream()
            .collect(Collectors.toMap(ServiceItem::getId, ItemService::toRef, (a, b) -> a));
    }

    @Override
    public void requireActiveItems(long shopId, Collection<Long> itemIds, String field) {
        Set<Long> ids = distinctIds(itemIds);
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> usable = items.findByShopIdAndIdIn(shopId, ids).stream()
            .filter(ServiceItem::isActive)
            .map(ServiceItem::getId)
            .collect(Collectors.toSet());
        if (!usable.containsAll(ids)) {
            throw BusinessException.invalidField(field, NOT_USABLE);
        }
    }

    private static Set<Long> distinctIds(Collection<Long> itemIds) {
        if (itemIds == null) {
            return Set.of();
        }
        return itemIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
    }

    private static ItemRef toRef(ServiceItem item) {
        return new ItemRef(item.getId(), item.getCode(), item.getName(), item.getCycleMonths().intValue(),
            item.getCycleKm(), item.isActive(), item.getSortOrder().intValue());
    }
}
