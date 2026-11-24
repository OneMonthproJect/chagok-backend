package com.wanted.chagok.domain.account.dto;

import com.wanted.chagok.global.auth.StaffRole;

/**
 * API 03 응답 (08 03). API 01 응답의 {@code staff}도 같은 모양이다. 프론트 공통 머리(정비소명·이름·역할)가 쓴다.
 * P1부터 계정 상태 필드(passwordChangeRequired 등)가 더해진다.
 */
public record MeResponse(long staffId, String name, StaffRole role, long shopId, String shopName) {
}
