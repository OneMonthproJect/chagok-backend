package com.wanted.chagok.domain.account.controller;

import com.wanted.chagok.domain.account.dto.AdminLoginResponse;
import com.wanted.chagok.domain.account.dto.LoginRequest;
import com.wanted.chagok.domain.account.dto.LoginResponse;
import com.wanted.chagok.domain.account.dto.MeResponse;
import com.wanted.chagok.domain.account.service.AuthService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 01 사장·정비사 로그인, 02 서비스 관리자 로그인, 03 내 계정 (08 2장). 요청 받기·{@code @Valid}·응답 변환만 하고
 * 업무 판단은 {@link AuthService}가 한다. 01·02만 토큰 없이 부를 수 있고(SecurityConfig), 03은 사장·정비사 토큰이 필요하다.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/admin/auth/login")
    public ApiResponse<AdminLoginResponse> adminLogin(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.adminLogin(request));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@CurrentStaff LoginStaff staff) {
        return ApiResponse.ok(authService.me(staff));
    }
}
