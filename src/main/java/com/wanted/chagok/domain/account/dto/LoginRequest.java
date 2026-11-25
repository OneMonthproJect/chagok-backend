package com.wanted.chagok.domain.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * API 01·02 요청 (08 01: 서비스 관리자 로그인 02도 요청이 같다). 로그인은 이미 있는 계정을 받으므로 신규 아이디 규칙(6~20자)·
 * 비밀번호 조합 규칙(8~20자)을 적용하지 않는다. 시연 계정 {@code 1234}도 그대로 들어온다 (08 01, D-44).
 *
 * <p>{@code toString}에 비밀번호를 싣지 않는다 — 로그·예외 메시지로 새지 않게 한다.
 */
public record LoginRequest(
        @NotNull @Size(min = 1, max = 50) String loginId,
        @NotNull @Size(min = 1, max = 100) String password) {

    @Override
    public String toString() {
        return "LoginRequest[loginId=" + loginId + ", password=***]";
    }
}
