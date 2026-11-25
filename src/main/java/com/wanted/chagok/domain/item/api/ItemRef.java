package com.wanted.chagok.domain.item.api;

/**
 * 정비 항목 한 개의 참조 (Entity가 아니라 값). 08 13번 응답 한 줄과 같은 뜻이라, 주기 계산({@code CycleRule})에 필요한
 * {@code cycleMonths}·{@code cycleKm}도 여기서 꺼낸다 (07 3.6).
 *
 * @param code      0.4 항목 값 (예: {@code ENGINE_OIL})
 * @param active    쓰는 항목이면 true. P0에서는 6개 모두 true (D-63 B01)
 * @param sortOrder 화면에 보이는 순서. 항목 상태 목록은 이 순서를 따른다 (D-63 B02)
 */
public record ItemRef(
    long itemId,
    String code,
    String name,
    int cycleMonths,
    int cycleKm,
    boolean active,
    int sortOrder
) {
}
