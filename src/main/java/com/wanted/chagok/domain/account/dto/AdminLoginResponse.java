package com.wanted.chagok.domain.account.dto;

/**
 * API 02 응답 (08 02). 토큰에 {@code shopId}가 없고, {@code admin.role}은 항상 {@code "ADMIN"}이다.
 * {@code toString}에 토큰을 싣지 않는다.
 */
public record AdminLoginResponse(String accessToken, String tokenType, long expiresIn, AdminInfo admin) {

    @Override
    public String toString() {
        return "AdminLoginResponse[accessToken=***, tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", admin=" + admin + "]";
    }

    public record AdminInfo(long adminId, String name, String role) {
    }
}
