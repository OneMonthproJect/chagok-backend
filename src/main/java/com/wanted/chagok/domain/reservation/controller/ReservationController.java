package com.wanted.chagok.domain.reservation.controller;

import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationListResponse;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 기간 조회(API 15)·등록(API 16). 요청 받기·검증·응답 변환만 하고 업무 판단은 service가 한다
 * (CONVENTIONS 5장). 정비소와 담당은 요청 값이 아니라 로그인 정보에서만 온다 (08 0.1, D-51).
 * 사장·정비사 모두 가능하며 권한은 {@code SecurityConfig}의 {@code /api/**} 규칙이 한 곳에서 본다.
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** API 15 — 기간 조회. {@code from}·{@code to}는 둘 다 필수이고 둘 다 포함, 최대 42일 (08 15번). */
    @GetMapping
    public ApiResponse<ReservationListResponse> list(
            @CurrentStaff LoginStaff staff,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reservationService.list(staff.shopId(), from, to));
    }

    /** API 16 — 예약 등록. 등록한 계정이 담당으로 저장된다 (D-51). */
    @PostMapping
    public ApiResponse<ReservationCreateResponse> create(
            @CurrentStaff LoginStaff staff,
            @Valid @RequestBody ReservationCreateRequest request) {
        return ApiResponse.ok(reservationService.create(staff.shopId(), staff.staffId(), request));
    }
}
