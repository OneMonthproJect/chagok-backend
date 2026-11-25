package com.wanted.chagok.domain.item.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * item이 다른 도메인에 주는 읽기 계약 (reservation 예약 목적 X11, maintenance 기록 저장·이력, screen/visit·booking이 쓴다).
 * 구현은 {@code ItemService}. 기존 메서드는 바꾸지 않고 메서드를 더한다 (CONVENTIONS 3장).
 * 모든 메서드는 {@code shopId}의 항목만 본다(I-3). 다른 정비소 항목은 없는 것과 같다.
 */
public interface ItemReader {

    /** 그 정비소가 쓰는({@code active}) 항목 전부, {@code sortOrder} 순 (API 10·17의 항목 상태 줄 순서, D-63 B02). */
    List<ItemRef> findActiveItems(long shopId);

    /**
     * 항목 id → 항목. 비활성 항목도 포함하되(과거 기록의 이름 표시용), 없거나 다른 정비소 항목은 결과에 없다(오류 아님).
     * 항목 이름 일괄 조회용이다(API 11·15·17, 건별 호출 금지). 입력이 비어 있으면 빈 Map.
     */
    Map<Long, ItemRef> findItems(long shopId, Collection<Long> itemIds);

    /**
     * 모든 id가 그 정비소가 쓰는 항목인지 확인한다 (예약 목적 X11, 방문 기록 {@code records[].itemId}).
     * 없음·남의 정비소 것·쓰지 않는 항목은 구분하지 않고 같은 400 {@code INVALID_INPUT}이며,
     * {@code fieldErrors}의 필드는 {@code field}(호출하는 쪽의 요청 필드 이름, 예: {@code purpose.itemIds})다.
     * 입력이 비어 있으면 통과.
     */
    void requireActiveItems(long shopId, Collection<Long> itemIds, String field);
}
