package com.wanted.chagok.domain.reservation.controller;

import com.wanted.chagok.domain.reservation.dto.ReservationCompleteRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 상태·일시를 바꾸는 API. 지금은 18 방문 완료만 있고, 19 변경은 item 계약(예약 목적 항목 검증)이 머지된 뒤에 더한다.
 * 요청 받기·검증·응답 변환만 하고 업무 판단은 service·Entity가 한다 (CONVENTIONS 5장).
 * 사장·정비사 모두 가능하며 권한은 {@code SecurityConfig}의 {@code /api/**} 규칙이 한 곳에서 본다.
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservationActionController {

    private final ReservationService reservationService;

    public ReservationActionController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** API 18 — 방문 완료. 정비 기록 저장(12)은 별도 요청이고 이 예약과 데이터로 잇지 않는다 (05 POL-95). */
    @PostMapping("/{reservationId}/complete")
    public ApiResponse<ReservationResponse> complete(@CurrentStaff LoginStaff staff,
                                                     @PathVariable long reservationId,
                                                     @Valid @RequestBody ReservationCompleteRequest request) {
        return ApiResponse.ok(reservationService.complete(staff.shopId(), reservationId, request.version()));
    }
}
