package com.wanted.chagok.domain.maintenance.controller;

import com.wanted.chagok.domain.maintenance.dto.RecordEditRequest;
import com.wanted.chagok.domain.maintenance.dto.RecordEditResponse;
import com.wanted.chagok.domain.maintenance.dto.RecordRevisionListResponse;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 37 정비 기록 수정·API 38 수정 이력 조회 (08 4장, F-MNT-04). 정비소는 토큰의 {@code shopId}로만 정한다. 삭제 경로는 없다(H9).
 */
@RestController
@RequestMapping("/api/records/{recordId}")
public class RecordEditController {

    private final MaintenanceService maintenanceService;

    public RecordEditController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @PatchMapping
    public ApiResponse<RecordEditResponse> edit(@CurrentStaff LoginStaff staff, @PathVariable long recordId,
        @Valid @RequestBody RecordEditRequest request) {
        return ApiResponse.ok(maintenanceService.editRecord(staff, recordId, request));
    }

    @GetMapping("/revisions")
    public ApiResponse<RecordRevisionListResponse> revisions(@CurrentStaff LoginStaff staff,
        @PathVariable long recordId) {
        return ApiResponse.ok(maintenanceService.revisions(staff.shopId(), recordId));
    }
}
