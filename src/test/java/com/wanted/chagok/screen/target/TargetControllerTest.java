package com.wanted.chagok.screen.target;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.cycle.api.CycleNote;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import com.wanted.chagok.screen.target.controller.TargetController;
import com.wanted.chagok.screen.target.dto.TargetListResponse;
import com.wanted.chagok.screen.target.service.TargetService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * API 14 재점검 대상 목록의 응답 모양 (08 14번). Service는 가짜, 보안 필터 없음(DB 없음).
 * 로그인 계정은 고정 {@code LoginStaff}(shopId 3)를 주입한다. 401·403은 EndpointGuardTest(db 태그).
 */
class TargetControllerTest {

    private static final LoginStaff STAFF = new LoginStaff(7L, 3L, StaffRole.MECHANIC);

    private final TargetService service = mock(TargetService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TargetController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(CurrentStaff.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return STAFF;
            }
        })
        .build();

    @Test
    @DisplayName("14 성공: 08 응답 모양, shopId는 토큰의 값, 예약 있음은 객체·없음은 null, targetItems의 lastDecline·lastCaution은 값이 있으면 { date, memo }·없으면 null")
    void success() throws Exception {
        when(service.getTargets(3L)).thenReturn(new TargetListResponse(LocalDate.of(2026, 11, 24), List.of(
            new TargetListResponse.Item(2L, "이영희", "010-0000-0002", 2L, "34나0002",
                List.of(new TargetListResponse.TargetItem(1L, "엔진오일", ItemStatus.DUE,
                    LocalDate.of(2026, 11, 14), LocalDate.of(2026, 5, 14),
                    new CycleNote(LocalDate.of(2026, 10, 24), "다음에 하겠다고 함"), null)),
                new TargetListResponse.ActiveReservation(3L, LocalDateTime.of(2026, 11, 26, 10, 0))),
            new TargetListResponse.Item(1L, "김민수", "010-0000-0001", 1L, "12가0001",
                List.of(new TargetListResponse.TargetItem(1L, "엔진오일", ItemStatus.DUE,
                    LocalDate.of(2026, 11, 17), LocalDate.of(2026, 5, 17), null, null)),
                null))));

        mvc.perform(get("/api/targets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.asOf").value("2026-11-24"))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].customerId").value(2))
            .andExpect(jsonPath("$.data.items[0].customerName").value("이영희"))
            .andExpect(jsonPath("$.data.items[0].phone").value("010-0000-0002"))
            .andExpect(jsonPath("$.data.items[0].vehicleId").value(2))
            .andExpect(jsonPath("$.data.items[0].plateNumber").value("34나0002"))
            .andExpect(jsonPath("$.data.items[0].targetItems[0].itemId").value(1))
            .andExpect(jsonPath("$.data.items[0].targetItems[0].name").value("엔진오일"))
            .andExpect(jsonPath("$.data.items[0].targetItems[0].status").value("DUE"))
            .andExpect(jsonPath("$.data.items[0].targetItems[0].nextDueDate").value("2026-11-14"))
            .andExpect(jsonPath("$.data.items[0].targetItems[0].baseDate").value("2026-05-14"))
            .andExpect(jsonPath("$.data.items[0].targetItems[0].lastDecline.date").value("2026-10-24"))
            .andExpect(jsonPath("$.data.items[0].targetItems[0].lastDecline.memo").value("다음에 하겠다고 함"))
            .andExpect(jsonPath("$.data.items[0].targetItems[0].lastCaution").isEmpty())
            .andExpect(jsonPath("$.data.items[1].targetItems[0].lastDecline").isEmpty())
            .andExpect(jsonPath("$.data.items[1].targetItems[0].lastCaution").isEmpty())
            .andExpect(jsonPath("$.data.items[0].activeReservation.reservationId").value(3))
            .andExpect(jsonPath("$.data.items[0].activeReservation.reservedAt").value("2026-11-26T10:00:00"))
            .andExpect(jsonPath("$.data.items[1].activeReservation").isEmpty());

        verify(service).getTargets(3L);
    }

    @Test
    @DisplayName("14 대상이 없으면 items는 빈 배열이다 (null이 아니다)")
    void empty() throws Exception {
        when(service.getTargets(3L)).thenReturn(new TargetListResponse(LocalDate.of(2026, 11, 24), List.of()));

        mvc.perform(get("/api/targets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }
}
