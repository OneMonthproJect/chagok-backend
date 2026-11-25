package com.wanted.chagok.domain.item.controller;

import com.wanted.chagok.domain.item.dto.ItemListResponse;
import com.wanted.chagok.domain.item.service.ItemService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.response.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API 13 정비 항목·주기 (08 5장 앞). 정비소는 토큰의 {@code shopId}로만 정한다(I-3, X7). */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping
    public ApiResponse<List<ItemListResponse>> list(@CurrentStaff LoginStaff staff) {
        return ApiResponse.ok(itemService.listItems(staff.shopId()));
    }
}
