package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.maintenance.controller.RecordEditController;
import com.wanted.chagok.domain.maintenance.dto.RecordEditRequest;
import com.wanted.chagok.domain.maintenance.dto.RecordEditResponse;
import com.wanted.chagok.domain.maintenance.dto.RecordRevisionListResponse;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.JacksonConfig;
import com.wanted.chagok.global.config.SecurityConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * API 37·38 (08 4장): 실제 보안 규칙·전역 처리기·요청 검증을 거친 요청/응답 모양. Service는 목(DB 없음).
 * 권한·기간 판정은 {@code MaintenanceEditServiceTest}, 정비소 격리·version 증가는 {@code MaintenanceEditDbTest}.
 */
@WebMvcTest(RecordEditController.class)
@Import({SecurityConfig.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "chagok.jwt.secret=" + RecordEditControllerTest.SECRET)
class RecordEditControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MaintenanceService maintenanceService;

    private String token(long staffId, long shopId, String role) {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(staffId, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    private ResultActions patchRecord(String body, String auth) throws Exception {
        var request = patch("/api/records/16").contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(auth == null ? request : request.header("Authorization", auth));
    }

    @Test
    @DisplayName("37: 토큰의 계정·경로의 recordId로 수정하고 08 모양({recordId, vehicleId, version})으로 응답")
    void edit_usesTokenAndReturnsSpecShape() throws Exception {
        when(maintenanceService.editRecord(any(LoginStaff.class), eq(16L), any(RecordEditRequest.class)))
            .thenReturn(new RecordEditResponse(16, 4, 1));

        patchRecord("""
            {"version": 0, "mileage": 52000, "memo": "합성유 사용", "reason": "입력 누락 보완", "shopId": 99}
            """, token(1, 7, "OWNER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.recordId").value(16))
            .andExpect(jsonPath("$.data.vehicleId").value(4))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.error").isEmpty());

        ArgumentCaptor<LoginStaff> staff = ArgumentCaptor.forClass(LoginStaff.class);
        ArgumentCaptor<RecordEditRequest> body = ArgumentCaptor.forClass(RecordEditRequest.class);
        verify(maintenanceService).editRecord(staff.capture(), eq(16L), body.capture());
        // X8: 본문의 shopId는 읽지 않고 토큰의 정비소·계정을 쓴다
        assertThat(staff.getValue()).isEqualTo(new LoginStaff(1, 7, StaffRole.OWNER));
        assertThat(body.getValue().version()).isZero();
        assertThat(body.getValue().mileage()).isEqualTo(52_000);
        assertThat(body.getValue().memo()).isEqualTo("합성유 사용");
        assertThat(body.getValue().reason()).isEqualTo("입력 누락 보완");
        assertThat(body.getValue().type()).isNull();
    }

    @Test
    @DisplayName("37: 구분·점검 결과를 enum으로 읽는다")
    void edit_readsTypeAndInspectResult() throws Exception {
        when(maintenanceService.editRecord(any(LoginStaff.class), eq(16L), any(RecordEditRequest.class)))
            .thenReturn(new RecordEditResponse(16, 4, 2));

        patchRecord("{\"version\": 1, \"type\": \"INSPECT\", \"inspectResult\": \"CAUTION\"}", token(1, 7, "OWNER"))
            .andExpect(status().isOk());

        ArgumentCaptor<RecordEditRequest> body = ArgumentCaptor.forClass(RecordEditRequest.class);
        verify(maintenanceService).editRecord(any(), eq(16L), body.capture());
        assertThat(body.getValue().type()).isEqualTo(RecordType.INSPECT);
        assertThat(body.getValue().inspectResult()).isEqualTo("CAUTION");
    }

    @Test
    @DisplayName("37: version이 없으면 400 INVALID_INPUT(version)이고 서비스를 부르지 않는다")
    void edit_missingVersion_400() throws Exception {
        patchRecord("{\"memo\": \"x\"}", token(1, 7, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("version"));

        verifyNoInteractions(maintenanceService);
    }

    @Test
    @DisplayName("37: version이 숫자가 아니거나 구분 값이 틀리면 400 INVALID_INPUT")
    void edit_badTypes_400() throws Exception {
        patchRecord("{\"version\": \"a\", \"memo\": \"x\"}", token(1, 7, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        patchRecord("{\"version\": 0, \"type\": \"NOPE\"}", token(1, 7, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verifyNoInteractions(maintenanceService);
    }

    @Test
    @DisplayName("37: 메모·사유가 200자를 넘으면 400 INVALID_INPUT")
    void edit_tooLongMemoAndReason_400() throws Exception {
        String longText = "가".repeat(201);

        patchRecord("{\"version\": 0, \"memo\": \"" + longText + "\"}", token(1, 7, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("memo"));
        patchRecord("{\"version\": 0, \"memo\": \"x\", \"reason\": \"" + longText + "\"}", token(1, 7, "OWNER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("reason"));

        verifyNoInteractions(maintenanceService);
    }

    @Test
    @DisplayName("37: 토큰이 없으면 401")
    void edit_noToken_401() throws Exception {
        patchRecord("{\"version\": 0, \"memo\": \"x\"}", null).andExpect(status().isUnauthorized());

        verifyNoInteractions(maintenanceService);
    }

    @Test
    @DisplayName("37: 서비스의 409·403 업무 오류는 08 오류 코드로 응답한다 (H3·H4·H10)")
    void edit_businessErrors() throws Exception {
        when(maintenanceService.editRecord(any(LoginStaff.class), eq(16L), any(RecordEditRequest.class)))
            .thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION))
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN))
            .thenThrow(new BusinessException(ErrorCode.EDIT_PERIOD_EXPIRED));

        patchRecord("{\"version\": 0, \"memo\": \"x\"}", token(2, 7, "MECHANIC"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
        patchRecord("{\"version\": 0, \"memo\": \"x\"}", token(2, 7, "MECHANIC"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        patchRecord("{\"version\": 0, \"memo\": \"x\"}", token(2, 7, "MECHANIC"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("EDIT_PERIOD_EXPIRED"));
    }

    @Test
    @DisplayName("38: 토큰의 shopId와 경로의 recordId로 조회하고 08 모양으로 응답 (값이 없으면 null)")
    void revisions_returnsSpecShape() throws Exception {
        when(maintenanceService.revisions(7L, 16L)).thenReturn(new RecordRevisionListResponse(List.of(
            new RecordRevisionListResponse.Item(1, LocalDateTime.parse("2026-11-25T10:05:00"),
                new RecordRevisionListResponse.Person(1, "박사장"), "입력 누락 보완",
                List.of(new RecordRevisionListResponse.Change("mileage", null, 52_000),
                    new RecordRevisionListResponse.Change("item", new RecordRevisionListResponse.ItemValue(1, "엔진오일"),
                        new RecordRevisionListResponse.ItemValue(2, "에어클리너")))),
            new RecordRevisionListResponse.Item(2, LocalDateTime.parse("2026-11-26T09:30:00"),
                new RecordRevisionListResponse.Person(1, "박사장"), null, List.of()))));

        mvc.perform(get("/api/records/16/revisions").header("Authorization", token(2, 7, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].revisionNo").value(1))
            .andExpect(jsonPath("$.data.items[0].changedAt").value("2026-11-25T10:05:00"))
            .andExpect(jsonPath("$.data.items[0].changedBy.staffId").value(1))
            .andExpect(jsonPath("$.data.items[0].changedBy.name").value("박사장"))
            .andExpect(jsonPath("$.data.items[0].reason").value("입력 누락 보완"))
            .andExpect(jsonPath("$.data.items[0].changes[0].field").value("mileage"))
            .andExpect(jsonPath("$.data.items[0].changes[0].before").isEmpty())
            .andExpect(jsonPath("$.data.items[0].changes[0].after").value(52_000))
            .andExpect(jsonPath("$.data.items[0].changes[1].after.name").value("에어클리너"))
            .andExpect(jsonPath("$.data.items[1].reason").isEmpty());
    }

    @Test
    @DisplayName("38: 이력이 없으면 items는 빈 배열, 남의 기록은 404 NOT_FOUND (X14)")
    void revisions_emptyAndNotFound() throws Exception {
        when(maintenanceService.revisions(7L, 16L)).thenReturn(new RecordRevisionListResponse(List.of()));
        when(maintenanceService.revisions(7L, 17L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mvc.perform(get("/api/records/16/revisions").header("Authorization", token(1, 7, "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/records/17/revisions").header("Authorization", token(1, 7, "OWNER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("H9: 삭제 경로는 없다 — DELETE /api/records/{id}는 서비스에 닿지 않는다")
    void delete_notAvailable() throws Exception {
        mvc.perform(delete("/api/records/16").header("Authorization", token(1, 7, "OWNER")))
            .andExpect(status().is4xxClientError());

        verifyNoInteractions(maintenanceService);
    }
}
