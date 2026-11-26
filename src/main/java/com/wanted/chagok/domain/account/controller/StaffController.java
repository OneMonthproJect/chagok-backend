package com.wanted.chagok.domain.account.controller;

import com.wanted.chagok.domain.account.dto.AssigneeListResponse;
import com.wanted.chagok.domain.account.service.StaffService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 36 담당 정비사 후보 조회 (08 7.1, F-RSV-01·06). 사장·정비사 토큰이 필요하고 서비스 관리자 토큰은 403이다(SecurityConfig).
 * 정비소는 요청 값이 아니라 토큰의 shopId로만 정한다. 요청 받기·응답 변환만 하고 조회는 {@link StaffService}가 한다.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping("/assignees")
    public ApiResponse<AssigneeListResponse> assignees(@CurrentStaff LoginStaff staff) {
        return ApiResponse.ok(AssigneeListResponse.from(staffService.findAssignees(staff.shopId())));
    }
}
