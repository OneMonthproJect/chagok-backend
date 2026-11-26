package com.wanted.chagok.screen.visit;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.JacksonConfig;
import com.wanted.chagok.global.config.SecurityConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import com.wanted.chagok.screen.visit.controller.CustomerDetailController;
import com.wanted.chagok.screen.visit.dto.CustomerDetailResponse;
import com.wanted.chagok.screen.visit.service.CustomerDetailService;
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
 * API 07 (08 07): 실제 보안 규칙·전역 처리기를 거친 요청/응답 모양. Service는 목(DB 없음).
 * 정비소 격리(X1)와 실제 개수는 {@code CustomerDetailSeedHttpDbTest}.
 */
@WebMvcTest(CustomerDetailController.class)
@Import({SecurityConfig.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "chagok.jwt.secret=" + CustomerDetailControllerTest.SECRET)
class CustomerDetailControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CustomerDetailService customerDetailService;

    private String token(long shopId, String role) {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(7, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    @Test
    @DisplayName("07: 토큰의 shopId·경로의 customerId로 조회하고 08 예시 모양(phone 전체, 비어 있는 값은 null)으로 응답")
    void detail_usesTokenShopAndReturnsSpecShape() throws Exception {
        when(customerDetailService.getCustomerDetail(1L, 10L)).thenReturn(new CustomerDetailResponse(10, "오세훈",
            "010-0000-0010", true, 2, List.of(
                new CustomerDetailResponse.Vehicle(10, "55차0010", "쏘나타", 2018, 97_000, 0, 1),
                new CustomerDetailResponse.Vehicle(11, "66카0011", "K3", null, null, 0, 0))));

        mvc.perform(get("/api/customers/10").header("Authorization", token(1, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.customerId").value(10))
            .andExpect(jsonPath("$.data.name").value("오세훈"))
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.vehicles[0].version").doesNotExist())
            .andExpect(jsonPath("$.data.phone").value("010-0000-0010"))
            .andExpect(jsonPath("$.data.consent").value(true))
            .andExpect(jsonPath("$.data.vehicles[0].vehicleId").value(10))
            .andExpect(jsonPath("$.data.vehicles[0].plateNumber").value("55차0010"))
            .andExpect(jsonPath("$.data.vehicles[0].model").value("쏘나타"))
            .andExpect(jsonPath("$.data.vehicles[0].modelYear").value(2018))
            .andExpect(jsonPath("$.data.vehicles[0].latestMileage").value(97_000))
            .andExpect(jsonPath("$.data.vehicles[0].dueCount").value(0))
            .andExpect(jsonPath("$.data.vehicles[0].soonCount").value(1))
            .andExpect(jsonPath("$.data.vehicles[1].modelYear").isEmpty())
            .andExpect(jsonPath("$.data.vehicles[1].latestMileage").isEmpty())
            .andExpect(jsonPath("$.data.vehicles[1].dueCount").value(0))
            .andExpect(jsonPath("$.data.vehicles[1].soonCount").value(0));
    }

    @Test
    @DisplayName("X1: 없거나 남의 정비소 고객이면 404 NOT_FOUND")
    void detail_notFound() throws Exception {
        when(customerDetailService.getCustomerDetail(anyLong(), eq(999L))).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mvc.perform(get("/api/customers/999").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("고객 번호가 숫자가 아니면 400 INVALID_INPUT")
    void detail_malformedId() throws Exception {
        mvc.perform(get("/api/customers/abc").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verifyNoInteractions(customerDetailService);
    }

    @Test
    @DisplayName("토큰 없음 401, 서비스 관리자 토큰 403 (X9·X10), 사장·정비사는 허용")
    void detail_authRules() throws Exception {
        mvc.perform(get("/api/customers/10")).andExpect(status().isUnauthorized());

        String admin = "Bearer " + TestJwts.sign(SECRET, TestJwts.admin(1, Instant.now().plusSeconds(3_600)));
        mvc.perform(get("/api/customers/10").header("Authorization", admin)).andExpect(status().isForbidden());
        verifyNoInteractions(customerDetailService);

        when(customerDetailService.getCustomerDetail(anyLong(), anyLong()))
            .thenReturn(new CustomerDetailResponse(10, "오세훈", "010-0000-0010", true, 0, List.of()));
        mvc.perform(get("/api/customers/10").header("Authorization", token(1, "OWNER"))).andExpect(status().isOk());
        mvc.perform(get("/api/customers/10").header("Authorization", token(1, "MECHANIC"))).andExpect(status().isOk());
    }
}
