package com.wanted.chagok.screen.target.controller;

import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.screen.target.dto.TargetListResponse;
import com.wanted.chagok.screen.target.service.TargetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API 14 재점검 대상 목록 (08 14). 읽기만 하는 GET이다. 정비소는 토큰의 {@code shopId}로만 정한다. */
@RestController
@RequestMapping("/api/targets")
public class TargetController {

    private final TargetService targetService;

    public TargetController(TargetService targetService) {
        this.targetService = targetService;
    }

    @GetMapping
    public ApiResponse<TargetListResponse> targets(@CurrentStaff LoginStaff staff) {
        return ApiResponse.ok(targetService.getTargets(staff.shopId()));
    }
}
