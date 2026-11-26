package com.wanted.chagok.domain.reservation.controller;

import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationListResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationRevisionListResponse;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 기간 조회(API 15)·등록(API 16)·이력 조회(API 41). 요청 받기·검증·응답 변환만 하고 업무 판단은 service가 한다
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

    /**
     * API 15 — 기간 조회. {@code from}·{@code to}는 둘 다 필수이고 둘 다 포함, 최대 42일 (08 15번).
     * {@code assigneeId}는 선택(P0-나머지): 그 담당의 예약만 보는 화면 필터이고 데이터 권한이 아니다.
     */
    @GetMapping
    public ApiResponse<ReservationListResponse> list(
            @CurrentStaff LoginStaff staff,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(reservationService.list(staff.shopId(), from, to, assigneeId));
    }

    /**
     * API 41 — 예약 이력(재배정·정정) 조회. 사장·정비사 모두 볼 수 있고 남의 예약은 404다(X14). 정비소는 토큰의 값만 쓴다.
     */
    @GetMapping("/{reservationId}/revisions")
    public ApiResponse<ReservationRevisionListResponse> revisions(@CurrentStaff LoginStaff staff,
                                                                  @PathVariable long reservationId) {
        return ApiResponse.ok(reservationService.revisions(staff.shopId(), reservationId));
    }

    /** API 16 — 예약 등록. 등록한 계정이 담당으로 저장된다 (D-51). */
    @PostMapping
    public ApiResponse<ReservationCreateResponse> create(
            @CurrentStaff LoginStaff staff,
            @Valid @RequestBody ReservationCreateRequest request) {
        return ApiResponse.ok(reservationService.create(staff.shopId(), staff.staffId(), request));
    }
}
