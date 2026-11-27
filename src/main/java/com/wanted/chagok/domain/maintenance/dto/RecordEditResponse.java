package com.wanted.chagok.domain.maintenance.dto;

/**
 * API 37 응답 (08 37번). 수정된 기록 전체는 싣지 않는다 — 화면은 11을 다시 조회한다.
 *
 * @param version 바뀐 {@code version} (요청 값 + 1)
 */
public record RecordEditResponse(long recordId, long vehicleId, int version) {
}
