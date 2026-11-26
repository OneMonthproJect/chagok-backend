package com.wanted.chagok.domain.reservation.controller;

import com.wanted.chagok.domain.reservation.dto.ReservationSummaryResponse;
import com.wanted.chagok.domain.reservation.service.ReservationSummaryService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 42 월 요약 (08 7.1 42, F-RSV-05). 요청 받기·응답 변환만 하고 집계는 service가 한다 (CONVENTIONS 5장).
 * 정비소는 요청 값이 아니라 로그인 정보에서만 온다. 사장·정비사 모두 가능하며 서비스 관리자 토큰은 403이다(SecurityConfig).
 */
@RestController
@RequestMapping("/api/reservations/summary")
public class ReservationSummaryController {

    private final ReservationSummaryService summaryService;

    public ReservationSummaryController(ReservationSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /** {@code from}·{@code to}는 둘 다 필수이고 둘 다 포함, 최대 42일 (08 15번과 같은 규칙). */
    @GetMapping
    public ApiResponse<ReservationSummaryResponse> summary(
            @CurrentStaff LoginStaff staff,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(summaryService.summarize(staff.shopId(), from, to));
    }
}
