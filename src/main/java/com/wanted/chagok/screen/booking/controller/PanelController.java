package com.wanted.chagok.screen.booking.controller;

import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.screen.booking.dto.PanelResponse;
import com.wanted.chagok.screen.booking.service.PanelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API 17 예약 상세 패널 (08 17). 읽기만 하는 GET이다. 정비소는 토큰의 {@code shopId}로만 정한다. */
@RestController
@RequestMapping("/api/reservations/{reservationId}/panel")
public class PanelController {

    private final PanelService panelService;

    public PanelController(PanelService panelService) {
        this.panelService = panelService;
    }

    @GetMapping
    public ApiResponse<PanelResponse> panel(@CurrentStaff LoginStaff staff, @PathVariable long reservationId) {
        return ApiResponse.ok(panelService.getPanel(staff.shopId(), reservationId));
    }
}
