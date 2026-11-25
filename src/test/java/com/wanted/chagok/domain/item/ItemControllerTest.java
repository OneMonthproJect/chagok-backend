package com.wanted.chagok.domain.item;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.item.controller.ItemController;
import com.wanted.chagok.domain.item.dto.ItemListResponse;
import com.wanted.chagok.domain.item.service.ItemService;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.JacksonConfig;
import com.wanted.chagok.global.config.SecurityConfig;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 13 (08 5장 앞): 실제 보안 규칙·전역 처리기를 거친 요청/응답 모양. Service는 목(DB 없음).
 * 정렬·정비소 격리 쿼리(X7)는 {@link ItemServiceDbTest}.
 */
@WebMvcTest(ItemController.class)
@Import({SecurityConfig.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "chagok.jwt.secret=" + ItemControllerTest.SECRET)
class ItemControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ItemService itemService;

    private String token(long shopId, String role) {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(7, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    @Test
    @DisplayName("13: 토큰의 shopId로 조회하고 08 모양(data는 배열)으로 응답")
    void list_usesTokenShopAndReturnsSpecShape() throws Exception {
        when(itemService.listItems(1L)).thenReturn(List.of(
            new ItemListResponse(1, "ENGINE_OIL", "엔진오일", 6, 10_000, true, 1),
            new ItemListResponse(2, "AIR_CLEANER", "에어클리너", 12, 15_000, true, 2)));

        mvc.perform(get("/api/items").header("Authorization", token(1, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].itemId").value(1))
            .andExpect(jsonPath("$.data[0].code").value("ENGINE_OIL"))
            .andExpect(jsonPath("$.data[0].name").value("엔진오일"))
            .andExpect(jsonPath("$.data[0].cycleMonths").value(6))
            .andExpect(jsonPath("$.data[0].cycleKm").value(10_000))
            .andExpect(jsonPath("$.data[0].active").value(true))
            .andExpect(jsonPath("$.data[0].sortOrder").value(1))
            .andExpect(jsonPath("$.data[1].code").value("AIR_CLEANER"));
    }

    @Test
    @DisplayName("X7·X8: 요청의 shopId=2는 무시하고 토큰의 shopId(1)로 조회")
    void x7_queryShopIdIsIgnored() throws Exception {
        when(itemService.listItems(anyLong())).thenReturn(List.of());

        mvc.perform(get("/api/items").param("shopId", "2").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());

        verify(itemService).listItems(1L);
    }

    @Test
    @DisplayName("토큰 없음 401, 서비스 관리자 토큰 403 (X9·X10)")
    void list_authRules() throws Exception {
        mvc.perform(get("/api/items")).andExpect(status().isUnauthorized());

        String admin = "Bearer " + TestJwts.sign(SECRET, TestJwts.admin(1, Instant.now().plusSeconds(3_600)));
        mvc.perform(get("/api/items").header("Authorization", admin)).andExpect(status().isForbidden());
    }
}
