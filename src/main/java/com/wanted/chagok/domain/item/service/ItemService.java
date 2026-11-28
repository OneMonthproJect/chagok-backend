package com.wanted.chagok.domain.item.service;

import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.item.dto.ItemListResponse;
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
 * 모든 조회는 {@code shopId}와 함께 한다(I-3). API 13(항목 목록)도 이 클래스가 만든다.
 */
@Service
@Transactional(readOnly = true)
public class ItemService implements ItemReader {

    private static final String NOT_USABLE = "사용할 수 없는 항목입니다.";

    private final ServiceItemRepository items;

    public ItemService(ServiceItemRepository items) {
        this.items = items;
    }

    /**
     * API 13. 그 정비소의 항목 전부를 {@code sortOrder} 순으로. 응답에 {@code active}가 있으므로 비활성도 싣는다
     * (P0는 6개 모두 active, 08 13 · D-63 B01). 쓰는 항목만 필요한 호출자는 {@link #findActiveItems}.
     */
    public List<ItemListResponse> listItems(long shopId) {
        return items.findByShopIdOrderBySortOrderAscIdAsc(shopId).stream()
            .map(ItemListResponse::from)
            .toList();
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

    /**
     * 받은 id로 조회하지 않고 그 정비소의 쓰는 항목을 읽어 메모리에서 비교한다. 그래서 요청 배열({@code records[]}·
     * {@code purpose.itemIds})이 길어도 DB 조회는 한 번이고 그 크기가 입력과 무관하다. 판정은 id로 조회할 때와 같다 —
     * 없음·남의 정비소·쓰지 않는 항목은 모두 쓰는 항목 목록에 없으므로 같은 400이다(X11).
     */
    // LIMIT: 정비소의 항목은 6개 고정이다(04 F-ITEM-01: 켜기·끄기·주기 수정만, 새 항목 추가는 P2). 항목 추가가 생겨
    // 정비소당 항목이 수백 개를 넘으면 이 전체 읽기를 다시 본다.
    @Override
    public void requireActiveItems(long shopId, Collection<Long> itemIds, String field) {
        Set<Long> ids = distinctIds(itemIds);
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> usable = items.findByShopIdAndActiveTrueOrderBySortOrderAscIdAsc(shopId).stream()
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
