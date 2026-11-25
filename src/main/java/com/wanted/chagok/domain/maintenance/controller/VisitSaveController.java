package com.wanted.chagok.domain.maintenance.controller;

import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveResponse;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
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
 * API 12 방문·정비 기록 저장 (08 4장). 방문 완료(API 18)와 별도 요청이다(05 POL-95). 정비소와 담당 정비사는 토큰으로만 정한다.
 * 이력 조회(API 11)는 {@link VisitController}.
 */
@RestController
@RequestMapping("/api/vehicles/{vehicleId}/visits")
public class VisitSaveController {

    private final MaintenanceService maintenanceService;

    public VisitSaveController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @PostMapping
    public ApiResponse<VisitSaveResponse> save(@CurrentStaff LoginStaff staff, @PathVariable long vehicleId,
        @Valid @RequestBody VisitSaveRequest request) {
        return ApiResponse.ok(maintenanceService.saveVisit(staff.shopId(), staff.staffId(), vehicleId, request));
    }
}
