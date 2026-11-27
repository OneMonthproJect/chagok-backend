package com.wanted.chagok.domain.maintenance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.maintenance.api.VisitRef;
import com.wanted.chagok.domain.maintenance.controller.VisitController;
import com.wanted.chagok.domain.maintenance.dto.VisitListResponse;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.JacksonConfig;
import com.wanted.chagok.global.config.SecurityConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import com.wanted.chagok.global.web.PageLimit;
import java.time.Instant;
import java.time.LocalDate;
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
 * API 11 (08 4장): 실제 보안 규칙·전역 처리기를 거친 요청/응답 모양. Service는 목(DB 없음).
 * 정비소 격리(X2)와 저장 정합성은 {@code MaintenanceServiceDbTest}.
 */
@WebMvcTest(VisitController.class)
@Import({SecurityConfig.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "chagok.jwt.secret=" + VisitControllerTest.SECRET)
class VisitControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MaintenanceService maintenanceService;

    private String token(long shopId, String role) {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(7, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    @Test
    @DisplayName("11: 토큰의 shopId·경로의 vehicleId로 조회하고 08 모양으로 응답")
    void list_usesTokenShopAndReturnsSpecShape() throws Exception {
        VisitRef visit = new VisitRef("5eed0000-0000-4000-8000-000000000015", LocalDate.parse("2026-10-24"), null, "김정비",
            List.of(
                new VisitRef.VisitRecord(16, 1, "엔진오일", "REPLACE", LocalDate.parse("2026-10-24"), null,
                    "SHOP", null, 0, false, new VisitRef.RecordStaff(2, "김정비")),
                new VisitRef.VisitRecord(17, 5, "타이어", "INSPECT", LocalDate.parse("2026-10-24"), "앞 타이어 마모",
                    "SHOP", "CAUTION", 2, true, new VisitRef.RecordStaff(2, "김정비")),
                new VisitRef.VisitRecord(18, 2, "에어클리너", "EXTERNAL", LocalDate.parse("2026-06-01"), null,
                    "CUSTOMER_STATEMENT", null, 0, false, new VisitRef.RecordStaff(2, "김정비"))));
        when(maintenanceService.listVisits(eq(1L), eq(4L), any(PageLimit.class)))
            .thenReturn(new VisitListResponse(List.of(visit), 0, 20, 3, 1));

        mvc.perform(get("/api/vehicles/4/visits").header("Authorization", token(1, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.items[0].visitKey").value("5eed0000-0000-4000-8000-000000000015"))
            .andExpect(jsonPath("$.data.items[0].visitDate").value("2026-10-24"))
            .andExpect(jsonPath("$.data.items[0].mileage").isEmpty())
            .andExpect(jsonPath("$.data.items[0].staffName").value("김정비"))
            .andExpect(jsonPath("$.data.items[0].records[0].recordId").value(16))
            .andExpect(jsonPath("$.data.items[0].records[0].itemId").value(1))
            .andExpect(jsonPath("$.data.items[0].records[0].itemName").value("엔진오일"))
            .andExpect(jsonPath("$.data.items[0].records[0].type").value("REPLACE"))
            .andExpect(jsonPath("$.data.items[0].records[0].date").value("2026-10-24"))
            .andExpect(jsonPath("$.data.items[0].records[0].memo").isEmpty())
            // 08 11 (#110 PR-B): source는 항상, inspectResult는 점검 결과가 있을 때만 값, 아니면 키는 있고 null
            .andExpect(jsonPath("$.data.items[0].records[0].source").value("SHOP"))
            .andExpect(jsonPath("$.data.items[0].records[0].inspectResult").hasJsonPath())
            .andExpect(jsonPath("$.data.items[0].records[0].inspectResult").isEmpty())
            .andExpect(jsonPath("$.data.items[0].records[1].source").value("SHOP"))
            .andExpect(jsonPath("$.data.items[0].records[1].inspectResult").value("CAUTION"))
            .andExpect(jsonPath("$.data.items[0].records[2].source").value("CUSTOMER_STATEMENT"))
            .andExpect(jsonPath("$.data.items[0].records[2].inspectResult").isEmpty())
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    @DisplayName("쪽 크기는 최대 50으로 자르고, 쪽 번호 음수는 400 INVALID_INPUT")
    void list_paging() throws Exception {
        when(maintenanceService.listVisits(anyLong(), anyLong(), any(PageLimit.class)))
            .thenReturn(new VisitListResponse(List.of(), 0, 50, 0, 0));

        mvc.perform(get("/api/vehicles/4/visits").param("size", "999").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isOk());
        verify(maintenanceService).listVisits(1L, 4L, new PageLimit(0, 50));

        mvc.perform(get("/api/vehicles/4/visits").param("page", "-1").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("page"));
    }

    @Test
    @DisplayName("X2: 없거나 남의 정비소 차량이면 404 NOT_FOUND")
    void list_notFound() throws Exception {
        when(maintenanceService.listVisits(anyLong(), anyLong(), any(PageLimit.class)))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mvc.perform(get("/api/vehicles/999/visits").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("토큰 없음 401, 서비스 관리자 토큰 403 (X9·X10)")
    void list_authRules() throws Exception {
        mvc.perform(get("/api/vehicles/4/visits")).andExpect(status().isUnauthorized());

        String admin = "Bearer " + TestJwts.sign(SECRET, TestJwts.admin(1, Instant.now().plusSeconds(3_600)));
        mvc.perform(get("/api/vehicles/4/visits").header("Authorization", admin)).andExpect(status().isForbidden());
    }
}
