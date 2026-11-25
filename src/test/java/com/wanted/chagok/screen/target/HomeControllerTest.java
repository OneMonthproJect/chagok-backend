package com.wanted.chagok.screen.target;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import com.wanted.chagok.screen.target.controller.HomeController;
import com.wanted.chagok.screen.target.dto.HomeResponse;
import com.wanted.chagok.screen.target.service.HomeService;
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
 * API 04 홈의 응답 모양 (08 04번). Service는 가짜, 보안 필터 없음(DB 없음).
 * 로그인 계정은 고정 {@code LoginStaff}(shopId 3)를 주입한다. 401·403은 EndpointGuardTest(db 태그)가 {@code /api/home}으로 본다.
 */
class HomeControllerTest {

    private static final LoginStaff STAFF = new LoginStaff(7L, 3L, StaffRole.MECHANIC);

    private final HomeService service = mock(HomeService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new HomeController(service))
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
    @DisplayName("04 성공: 08 응답 모양, shopId는 토큰의 값, P0-핵심 오늘 예약 줄에는 assignee가 없다")
    void success() throws Exception {
        when(service.getHome(3L)).thenReturn(new HomeResponse(LocalDate.of(2026, 11, 24), 2, 5, List.of(
            new HomeResponse.TodayReservation(1L, LocalDateTime.of(2026, 11, 24, 10, 0), "RESERVED", false, 0, 4L,
                "78라0004", 4L, "최지은", "010-0000-0004", "엔진오일"),
            new HomeResponse.TodayReservation(2L, LocalDateTime.of(2026, 11, 24, 14, 30), "RESERVED", false, 0, 7L,
                "22사0007", 7L, "서민준", "010-0000-0007", null))));

        mvc.perform(get("/api/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.date").value("2026-11-24"))
            .andExpect(jsonPath("$.data.todayReservationCount").value(2))
            .andExpect(jsonPath("$.data.todayContactCount").value(5))
            .andExpect(jsonPath("$.data.todayReservations.length()").value(2))
            .andExpect(jsonPath("$.data.todayReservations[0].reservationId").value(1))
            .andExpect(jsonPath("$.data.todayReservations[0].reservedAt").value("2026-11-24T10:00:00"))
            .andExpect(jsonPath("$.data.todayReservations[0].status").value("RESERVED"))
            .andExpect(jsonPath("$.data.todayReservations[0].needsAction").value(false))
            .andExpect(jsonPath("$.data.todayReservations[0].version").value(0))
            .andExpect(jsonPath("$.data.todayReservations[0].vehicleId").value(4))
            .andExpect(jsonPath("$.data.todayReservations[0].plateNumber").value("78라0004"))
            .andExpect(jsonPath("$.data.todayReservations[0].customerId").value(4))
            .andExpect(jsonPath("$.data.todayReservations[0].customerName").value("최지은"))
            .andExpect(jsonPath("$.data.todayReservations[0].phone").value("010-0000-0004"))
            .andExpect(jsonPath("$.data.todayReservations[0].purposeSummary").value("엔진오일"))
            .andExpect(jsonPath("$.data.todayReservations[0].assignee").doesNotExist())
            .andExpect(jsonPath("$.data.todayReservations[1].purposeSummary").isEmpty());

        verify(service).getHome(3L);
    }

    @Test
    @DisplayName("04 예약이 없으면 todayReservations는 빈 배열이다 (null이 아니다)")
    void empty() throws Exception {
        when(service.getHome(3L)).thenReturn(new HomeResponse(LocalDate.of(2026, 11, 24), 0, 0, List.of()));

        mvc.perform(get("/api/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.todayReservations").isArray())
            .andExpect(jsonPath("$.data.todayReservations.length()").value(0));
    }
}
