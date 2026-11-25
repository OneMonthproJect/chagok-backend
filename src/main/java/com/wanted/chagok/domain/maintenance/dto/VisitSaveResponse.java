package com.wanted.chagok.domain.maintenance.dto;

/**
 * API 12 응답 (08 12). {@code visitKey}는 서버가 이 요청 하나마다 새로 만든 방문 묶음 번호이고, {@code savedCount}는 저장한 기록(항목) 수다.
 */
public record VisitSaveResponse(long vehicleId, String visitKey, int savedCount) {
}
