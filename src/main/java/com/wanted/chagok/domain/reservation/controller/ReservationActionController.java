package com.wanted.chagok.domain.reservation.controller;

import com.wanted.chagok.domain.reservation.dto.ReservationCompleteRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationStartRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 상태·일시를 바꾸는 API: 18 방문 완료, 19 변경, 39 방문 접수.
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

    /**
     * API 39 — 방문 접수(예약 → 진행중). 차량의 연식·최근 주행거리가 비어 있으면 같은 요청의 값을 차량에 저장한다(D-58).
     * 접수 뒤 정비 기록은 별도 요청이다 (05 POL-95).
     */
    @PostMapping("/{reservationId}/start")
    public ApiResponse<ReservationResponse> start(@CurrentStaff LoginStaff staff,
                                                  @PathVariable long reservationId,
                                                  @Valid @RequestBody ReservationStartRequest request) {
        return ApiResponse.ok(reservationService.start(staff.shopId(), reservationId, request));
    }

    /** API 18 — 방문 완료. 정비 기록 저장(12)은 별도 요청이고 이 예약과 데이터로 잇지 않는다 (05 POL-95). */
    @PostMapping("/{reservationId}/complete")
    public ApiResponse<ReservationResponse> complete(@CurrentStaff LoginStaff staff,
                                                     @PathVariable long reservationId,
                                                     @Valid @RequestBody ReservationCompleteRequest request) {
        return ApiResponse.ok(reservationService.complete(staff.shopId(), reservationId, request.version()));
    }

    /**
     * API 19 — 예약 변경. 보낸 필드만 바꾸고(일시·예약 목적), 바꿀 필드가 없으면 400이다. 고객·차량은 바꿀 수 없다.
     * 담당 재배정(assigneeId)은 P0-나머지라 읽지 않는다.
     */
    @PatchMapping("/{reservationId}")
    public ApiResponse<ReservationResponse> update(@CurrentStaff LoginStaff staff,
                                                   @PathVariable long reservationId,
                                                   @Valid @RequestBody ReservationUpdateRequest request) {
        return ApiResponse.ok(reservationService.update(staff.shopId(), reservationId, request));
    }
}
