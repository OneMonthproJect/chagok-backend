package com.wanted.chagok.screen.visit.controller;

import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.screen.visit.dto.CustomerDetailResponse;
import com.wanted.chagok.screen.visit.service.CustomerDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API 07 고객 상세 (08 07). 정비소는 토큰의 {@code shopId}로만 정한다. 목록·등록(API 05·06)은 {@code domain/customer}의 컨트롤러다. */
@RestController
@RequestMapping("/api/customers/{customerId}")
public class CustomerDetailController {

    private final CustomerDetailService customerDetailService;

    public CustomerDetailController(CustomerDetailService customerDetailService) {
        this.customerDetailService = customerDetailService;
    }

    @GetMapping
    public ApiResponse<CustomerDetailResponse> detail(@CurrentStaff LoginStaff staff, @PathVariable long customerId) {
        return ApiResponse.ok(customerDetailService.getCustomerDetail(staff.shopId(), customerId));
    }
}
