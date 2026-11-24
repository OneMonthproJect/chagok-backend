package com.wanted.chagok.domain.maintenance.controller;

import com.wanted.chagok.domain.maintenance.dto.VisitListResponse;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.global.web.PageLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API 11 방문 단위 정비 이력 (08 4장). 정비소는 토큰의 {@code shopId}로만 정한다. 저장(API 12)은 #42의 별도 컨트롤러. */
@RestController
@RequestMapping("/api/vehicles/{vehicleId}/visits")
public class VisitController {

    private final MaintenanceService maintenanceService;

    public VisitController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @GetMapping
    public ApiResponse<VisitListResponse> list(@CurrentStaff LoginStaff staff, @PathVariable long vehicleId,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(maintenanceService.listVisits(staff.shopId(), vehicleId, PageLimit.of(page, size)));
    }
}
