package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.maintenance.controller.VisitSaveController;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveResponse;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.JacksonConfig;
import com.wanted.chagok.global.config.SecurityConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 12 (08 4장): 실제 보안 규칙·전역 처리기·요청 검증을 거친 요청/응답 모양. Service는 목(DB 없음).
 * 정비소 격리(X5)·저장 정합성은 {@code MaintenanceSaveDbTest}·{@code MaintenanceSaveRollbackDbTest}.
 */
@WebMvcTest(VisitSaveController.class)
@Import({SecurityConfig.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "chagok.jwt.secret=" + VisitSaveControllerTest.SECRET)
class VisitSaveControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    private static final String VALID = """
        {"visitDate": "2026-11-24", "mileage": 55000,
         "records": [{"itemId": 1, "type": "REPLACE", "memo": null},
                     {"itemId": 5, "type": "INSPECT", "memo": "앞 타이어 마모 확인"}]}
        """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MaintenanceService maintenanceService;

    private String token(long staffId, long shopId, String role) {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(staffId, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    private org.springframework.test.web.servlet.ResultActions save(String vehicleId, String body, String auth)
        throws Exception {
        var request = post("/api/vehicles/" + vehicleId + "/visits").contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(auth == null ? request : request.header("Authorization", auth));
    }

    @Test
    @DisplayName("12: 토큰의 shopId·staffId와 경로의 vehicleId로 저장하고 08 모양(vehicleId·visitKey·savedCount)으로 응답")
    void save_usesTokenAndReturnsSpecShape() throws Exception {
        when(maintenanceService.saveVisit(eq(1L), eq(7L), eq(4L), any(VisitSaveRequest.class)))
            .thenReturn(new VisitSaveResponse(4, "3f1c2b9e-0000-4000-8000-000000000012", 2));

        save("4", VALID, token(7, 1, "MECHANIC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.vehicleId").value(4))
            .andExpect(jsonPath("$.data.visitKey").value("3f1c2b9e-0000-4000-8000-000000000012"))
            .andExpect(jsonPath("$.data.savedCount").value(2));

        ArgumentCaptor<VisitSaveRequest> captor = ArgumentCaptor.forClass(VisitSaveRequest.class);
        verify(maintenanceService).saveVisit(eq(1L), eq(7L), eq(4L), captor.capture());
        VisitSaveRequest sent = captor.getValue();
        assertThat(sent.visitDate()).isEqualTo(LocalDate.parse("2026-11-24"));
        assertThat(sent.mileage()).isEqualTo(55_000);
        assertThat(sent.records()).extracting(VisitSaveRequest.RecordInput::itemId).containsExactly(1L, 5L);
        assertThat(sent.records()).extracting(VisitSaveRequest.RecordInput::type)
            .containsExactly(RecordType.REPLACE, RecordType.INSPECT);
        assertThat(sent.records().get(1).memo()).isEqualTo("앞 타이어 마모 확인");
    }

    @Test
    @DisplayName("X8: 본문의 shopId는 읽지 않고, P0-나머지 필드(mechanicId 등)는 서비스가 400으로 거절할 수 있게 그대로 전달된다")
    void save_ignoresBodyShopId_andPassesNotOpenFields() throws Exception {
        when(maintenanceService.saveVisit(anyLong(), anyLong(), anyLong(), any(VisitSaveRequest.class)))
            .thenReturn(new VisitSaveResponse(4, "k", 1));
        String body = """
            {"shopId": 99, "visitDate": "2026-11-24", "mileage": 1000, "mechanicId": 2,
             "records": [{"itemId": 1, "type": "EXTERNAL", "externalDate": "2026-10-01", "externalMileage": 500,
                          "inspectResult": "GOOD"}]}
            """;

        save("4", body, token(7, 1, "OWNER")).andExpect(status().isOk());

        ArgumentCaptor<VisitSaveRequest> captor = ArgumentCaptor.forClass(VisitSaveRequest.class);
        verify(maintenanceService).saveVisit(eq(1L), eq(7L), eq(4L), captor.capture());
        VisitSaveRequest sent = captor.getValue();
        assertThat(sent.mechanicId()).isEqualTo(2L);
        assertThat(sent.records().get(0).type()).isEqualTo(RecordType.EXTERNAL);
        assertThat(sent.records().get(0).externalDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(sent.records().get(0).externalMileage()).isEqualTo(500);
        assertThat(sent.records().get(0).inspectResult()).isEqualTo("GOOD");
    }

    @Test
    @DisplayName("형식 오류는 400 INVALID_INPUT + fieldErrors: visitDate 없음, 기록 0개, itemId·type 없음, 메모 200자 초과")
    void save_validation() throws Exception {
        save("4", """
            {"mileage": 1000, "records": []}
            """, token(7, 1, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[?(@.field=='visitDate')]").isNotEmpty())
            .andExpect(jsonPath("$.error.fieldErrors[?(@.field=='records')]").isNotEmpty());

        save("4", """
            {"visitDate": "2026-11-24", "records": [{"memo": "ok"}]}
            """, token(7, 1, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[?(@.field=='records[0].itemId')]").isNotEmpty())
            .andExpect(jsonPath("$.error.fieldErrors[?(@.field=='records[0].type')]").isNotEmpty());

        save("4", """
            {"visitDate": "2026-11-24", "records": [null]}
            """, token(7, 1, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        String longMemo = "가".repeat(201);
        save("4", "{\"visitDate\": \"2026-11-24\", \"records\": [{\"itemId\": 1, \"type\": \"REPLACE\", \"memo\": \""
            + longMemo + "\"}]}", token(7, 1, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[?(@.field=='records[0].memo')]").isNotEmpty());
        verifyNoInteractions(maintenanceService);
    }

    @Test
    @DisplayName("알 수 없는 구분 값·정수 자리의 소수·날짜 형식 오류는 400 INVALID_INPUT")
    void save_malformedValues() throws Exception {
        save("4", """
            {"visitDate": "2026-11-24", "records": [{"itemId": 1, "type": "SWAP"}]}
            """, token(7, 1, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        save("4", """
            {"visitDate": "2026-11-24", "mileage": 1.5, "records": [{"itemId": 1, "type": "REPLACE"}]}
            """, token(7, 1, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        save("4", """
            {"visitDate": "24-11-2026", "records": [{"itemId": 1, "type": "REPLACE"}]}
            """, token(7, 1, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verifyNoInteractions(maintenanceService);
    }

    @Test
    @DisplayName("X5: 없거나 남의 정비소 차량이면 404 NOT_FOUND, 서비스의 400은 그대로 400")
    void save_serviceErrors() throws Exception {
        when(maintenanceService.saveVisit(anyLong(), anyLong(), eq(999L), any(VisitSaveRequest.class)))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        when(maintenanceService.saveVisit(anyLong(), anyLong(), eq(4L), any(VisitSaveRequest.class)))
            .thenThrow(BusinessException.invalidField("visitDate", "오늘 이전 날짜만 입력할 수 있습니다."));

        save("999", VALID, token(7, 1, "OWNER"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        save("4", VALID, token(7, 1, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("visitDate"));
    }

    @Test
    @DisplayName("토큰 없음 401, 서비스 관리자 토큰 403 (X9·X10), 사장·정비사는 허용")
    void save_authRules() throws Exception {
        save("4", VALID, null).andExpect(status().isUnauthorized());

        String admin = "Bearer " + TestJwts.sign(SECRET, TestJwts.admin(1, Instant.now().plusSeconds(3_600)));
        save("4", VALID, admin).andExpect(status().isForbidden());
        verifyNoInteractions(maintenanceService);

        when(maintenanceService.saveVisit(anyLong(), anyLong(), anyLong(), any(VisitSaveRequest.class)))
            .thenReturn(new VisitSaveResponse(4, "k", 2));
        save("4", VALID, token(7, 1, "OWNER")).andExpect(status().isOk());
        save("4", VALID, token(8, 1, "MECHANIC")).andExpect(status().isOk());
    }
}
