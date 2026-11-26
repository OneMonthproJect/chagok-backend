package com.wanted.chagok.domain.customer.controller;

import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerCreateResponse;
import com.wanted.chagok.domain.customer.dto.CustomerSearchResponse;
import com.wanted.chagok.domain.customer.dto.CustomerUpdateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerUpdateResponse;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.global.web.PageLimit;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API 05 검색·목록, 06 신규 고객 + 첫 차량 (08 4장), 22 고객 수정 (08 7장). 정비소는 토큰의 {@code shopId}로만 정한다(X8: 요청의 shopId는 읽지 않는다). */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public ApiResponse<CustomerSearchResponse> search(@CurrentStaff LoginStaff staff,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(customerService.search(staff.shopId(), q, PageLimit.of(page, size)));
    }

    @PatchMapping("/{customerId}")
    public ApiResponse<CustomerUpdateResponse> update(@CurrentStaff LoginStaff staff, @PathVariable long customerId,
        @Valid @RequestBody CustomerUpdateRequest request) {
        return ApiResponse.ok(customerService.updateCustomer(staff.shopId(), customerId, request));
    }

    @PostMapping
    public ApiResponse<CustomerCreateResponse> register(@CurrentStaff LoginStaff staff,
        @Valid @RequestBody CustomerCreateRequest request) {
        return ApiResponse.ok(customerService.register(staff.shopId(), request));
    }
}
