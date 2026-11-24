package com.wanted.chagok.domain.account.dto;

/**
 * API 01 응답 (08 01). {@code expiresIn}은 초(43200 = 12시간). {@code toString}에 토큰을 싣지 않는다.
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, MeResponse staff) {

    public static final String BEARER = "Bearer";

    @Override
    public String toString() {
        return "LoginResponse[accessToken=***, tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", staff=" + staff + "]";
    }
}
