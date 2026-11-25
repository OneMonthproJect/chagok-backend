package com.wanted.chagok.screen.visit;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.cycle.api.BaseRecord;
import com.wanted.chagok.domain.cycle.api.DueReason;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.JacksonConfig;
import com.wanted.chagok.global.config.SecurityConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import com.wanted.chagok.screen.visit.controller.ItemStatusController;
import com.wanted.chagok.screen.visit.dto.ItemStatusResponse;
import com.wanted.chagok.screen.visit.service.ItemStatusService;
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

/** API 10 (08 10): 실제 보안 규칙·검증·전역 처리기를 거친 요청/응답 모양. Service는 목(DB 없음). */
@WebMvcTest(ItemStatusController.class)
@Import({SecurityConfig.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "chagok.jwt.secret=" + ItemStatusControllerTest.SECRET)
class ItemStatusControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ItemStatusService itemStatusService;

    private String token(long shopId, String role) {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(7, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    @Test
    @DisplayName("08 10 응답 모양: 토큰의 shopId·경로 차량·mileage로 조회하고 data 필드가 명세와 같다")
    void returnsSpecShape() throws Exception {
        when(itemStatusService.getItemStatus(1L, 4L, 55_000)).thenReturn(
            new ItemStatusResponse(4, LocalDate.of(2026, 11, 24), 55_000, List.of(
                new ItemStatusResponse.Item(1, "ENGINE_OIL", "엔진오일", ItemStatus.DUE, DueReason.KM,
                    new BaseRecord(LocalDate.of(2026, 9, 24), 45_000, "REPLACE"), LocalDate.of(2027, 3, 24), 55_000),
                new ItemStatusResponse.Item(2, "AIR_CLEANER", "에어클리너", ItemStatus.UNKNOWN, null, null, null, null))));

        mvc.perform(get("/api/vehicles/4/item-status").param("mileage", "55000")
                .header("Authorization", token(1, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.vehicleId").value(4))
            .andExpect(jsonPath("$.data.asOf").value("2026-11-24"))
            .andExpect(jsonPath("$.data.inputMileage").value(55000))
            .andExpect(jsonPath("$.data.items[0].status").value("DUE"))
            .andExpect(jsonPath("$.data.items[0].dueReason").value("KM"))
            .andExpect(jsonPath("$.data.items[0].baseRecord.type").value("REPLACE"))
            .andExpect(jsonPath("$.data.items[0].baseRecord.date").value("2026-09-24"))
            .andExpect(jsonPath("$.data.items[0].nextDueDate").value("2027-03-24"))
            .andExpect(jsonPath("$.data.items[0].nextDueMileage").value(55000))
            .andExpect(jsonPath("$.data.items[1].status").value("UNKNOWN"))
            .andExpect(jsonPath("$.data.items[1].baseRecord").isEmpty());
    }

    @Test
    @DisplayName("mileage를 안 주면 null로 넘기고, 요청의 shopId=2는 무시하고 토큰의 shopId(1)를 쓴다 (X8 계열)")
    void noMileageAndShopIdParamIgnored() throws Exception {
        when(itemStatusService.getItemStatus(1L, 4L, null))
            .thenReturn(new ItemStatusResponse(4, LocalDate.of(2026, 11, 24), null, List.of()));

        mvc.perform(get("/api/vehicles/4/item-status").param("shopId", "2").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.inputMileage").isEmpty());

        verify(itemStatusService).getItemStatus(1L, 4L, null);
    }

    @Test
    @DisplayName("X6 서비스가 404를 던지면 404 NOT_FOUND")
    void x6_notFound() throws Exception {
        when(itemStatusService.getItemStatus(1L, 99L, 100)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mvc.perform(get("/api/vehicles/99/item-status").param("mileage", "100")
                .header("Authorization", token(1, "OWNER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("mileage가 정수가 아니면 400 INVALID_INPUT + mileage fieldErrors")
    void mileageNotInteger() throws Exception {
        mvc.perform(get("/api/vehicles/4/item-status").param("mileage", "12.5")
                .header("Authorization", token(1, "OWNER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("mileage"));
    }

    @Test
    @DisplayName("토큰이 없으면 401")
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/vehicles/4/item-status")).andExpect(status().isUnauthorized());
    }
}
