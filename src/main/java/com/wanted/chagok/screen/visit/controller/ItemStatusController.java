package com.wanted.chagok.screen.visit.controller;

import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.screen.visit.dto.ItemStatusResponse;
import com.wanted.chagok.screen.visit.service.ItemStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API 10 항목 상태·저장 전 미리보기 (08 10). 기록을 남기지 않는 GET이다. 정비소는 토큰의 {@code shopId}로만 정한다. */
@RestController
@RequestMapping("/api/vehicles/{vehicleId}/item-status")
public class ItemStatusController {

    private final ItemStatusService itemStatusService;

    public ItemStatusController(ItemStatusService itemStatusService) {
        this.itemStatusService = itemStatusService;
    }

    @GetMapping
    public ApiResponse<ItemStatusResponse> itemStatus(@CurrentStaff LoginStaff staff,
        @PathVariable long vehicleId, @RequestParam(required = false) Integer mileage) {
        return ApiResponse.ok(itemStatusService.getItemStatus(staff.shopId(), vehicleId, mileage));
    }
}
