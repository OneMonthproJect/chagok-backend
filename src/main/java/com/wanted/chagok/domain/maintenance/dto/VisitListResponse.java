package com.wanted.chagok.domain.maintenance.dto;

import com.wanted.chagok.domain.maintenance.api.VisitRef;
import java.util.List;

/**
 * API 11 응답 (08 11). {@code items[]}의 한 줄은 {@link VisitRef}와 같은 모양이라 그대로 쓴다.
 * 쪽 나누기와 {@code totalElements}는 방문 묶음 수 기준이다(기록 행 수가 아님).
 */
public record VisitListResponse(
    List<VisitRef> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
