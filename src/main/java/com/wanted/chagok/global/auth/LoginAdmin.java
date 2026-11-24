package com.wanted.chagok.global.auth;

/**
 * 로그인한 서비스 관리자 ({@code platform_admin}). {@code shopId}가 없다 — 정비소 데이터를 조회할 기준 자체가 없다 (07 I-5, 11 3.3).
 * {@code /api/admin/**}에서만 쓰인다(SecurityConfig).
 */
public record LoginAdmin(long adminId) {
}
