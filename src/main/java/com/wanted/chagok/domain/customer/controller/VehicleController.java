package com.wanted.chagok.domain.customer.controller;

import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.dto.VehicleAddResponse;
import com.wanted.chagok.domain.customer.dto.VehicleDetailResponse;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 08 차량 추가, 09 차량 상세 (08 4장). 정비소는 토큰의 {@code shopId}로만 정한다(X8: 요청의 shopId는 읽지 않는다).
 * 08의 요청 본문은 06의 {@code vehicle}과 같아서 같은 검증을 가진 {@code VehicleInput}을 그대로 쓴다.
 */
@RestController
public class VehicleController {

    private final CustomerService customerService;

    public VehicleController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping("/api/customers/{customerId}/vehicles")
    public ApiResponse<VehicleAddResponse> addVehicle(@CurrentStaff LoginStaff staff, @PathVariable long customerId,
        @Valid @RequestBody CustomerCreateRequest.VehicleInput request) {
        return ApiResponse.ok(customerService.addVehicle(staff.shopId(), customerId, request));
    }

    @GetMapping("/api/vehicles/{vehicleId}")
    public ApiResponse<VehicleDetailResponse> detail(@CurrentStaff LoginStaff staff, @PathVariable long vehicleId) {
        return ApiResponse.ok(customerService.getVehicleDetail(staff.shopId(), vehicleId));
    }
}
