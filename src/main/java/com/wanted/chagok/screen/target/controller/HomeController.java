package com.wanted.chagok.screen.target.controller;

import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.screen.target.dto.HomeResponse;
import com.wanted.chagok.screen.target.service.HomeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API 04 홈 (08 04). 읽기만 하는 GET이다. 정비소는 토큰의 {@code shopId}로만 정한다. */
@RestController
@RequestMapping("/api/home")
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping
    public ApiResponse<HomeResponse> home(@CurrentStaff LoginStaff staff) {
        return ApiResponse.ok(homeService.getHome(staff.shopId()));
    }
}
