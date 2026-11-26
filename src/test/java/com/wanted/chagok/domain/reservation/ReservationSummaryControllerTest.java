package com.wanted.chagok.domain.reservation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.controller.ReservationSummaryController;
import com.wanted.chagok.domain.reservation.dto.ReservationSummaryResponse;
import com.wanted.chagok.domain.reservation.service.ReservationSummaryService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.time.LocalDate;
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
 * API 42 월 요약의 요청·응답 모양과 오류 매핑 (08 7.1 42). Service는 가짜, 보안 필터 없음(DB 없음).
 * 로그인 계정은 고정 {@code LoginStaff}(shopId 3)를 주입한다. 401·403은 {@code ReservationSummaryDbTest}.
 */
class ReservationSummaryControllerTest {

    private static final LoginStaff STAFF = new LoginStaff(7L, 3L, StaffRole.MECHANIC);

    private final ReservationSummaryService service = mock(ReservationSummaryService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReservationSummaryController(service))
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
    @DisplayName("42 성공: data { from, to, items[{ date, total, reserved, inProgress, completed, canceled, noShow }] }, shopId는 토큰 값")
    void responseShape() throws Exception {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);
        when(service.summarize(3L, from, to)).thenReturn(new ReservationSummaryResponse(from, to, List.of(
            new ReservationSummaryResponse.Item(LocalDate.of(2026, 11, 23), 3, 2, 0, 1, 1, 0),
            new ReservationSummaryResponse.Item(LocalDate.of(2026, 11, 24), 1, 0, 1, 0, 0, 0))));

        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-01").param("to", "2026-11-30").param("shopId", "99"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.from").value("2026-11-01"))
            .andExpect(jsonPath("$.data.to").value("2026-11-30"))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].date").value("2026-11-23"))
            .andExpect(jsonPath("$.data.items[0].total").value(3))
            .andExpect(jsonPath("$.data.items[0].reserved").value(2))
            .andExpect(jsonPath("$.data.items[0].inProgress").value(0))
            .andExpect(jsonPath("$.data.items[0].completed").value(1))
            .andExpect(jsonPath("$.data.items[0].canceled").value(1))
            .andExpect(jsonPath("$.data.items[0].noShow").value(0))
            .andExpect(jsonPath("$.data.items[1].date").value("2026-11-24"))
            .andExpect(jsonPath("$.data.items[1].inProgress").value(1));
        verify(service).summarize(3L, from, to);
    }

    @Test
    @DisplayName("42 예약이 없으면 items는 [] (null이 아님)")
    void emptyItems() throws Exception {
        LocalDate from = LocalDate.of(2026, 12, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        when(service.summarize(3L, from, to)).thenReturn(new ReservationSummaryResponse(from, to, List.of()));

        mvc.perform(get("/api/reservations/summary").param("from", "2026-12-01").param("to", "2026-12-31"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("42 from·to 누락·날짜 형식 오류 → 400 INVALID_INPUT, service 호출 없음")
    void invalidParams() throws Exception {
        mvc.perform(get("/api/reservations/summary").param("to", "2026-11-30"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("from"));
        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("to"));
        mvc.perform(get("/api/reservations/summary").param("from", "abc").param("to", "2026-11-30"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verify(service, never()).summarize(anyLong(), any(), any());
    }

    @Test
    @DisplayName("42 기간 규칙 위반(시작>끝·42일 초과)은 service의 400 INVALID_INPUT + fieldErrors가 그대로 나간다")
    void periodRule() throws Exception {
        LocalDate from = LocalDate.of(2026, 11, 5);
        LocalDate to = LocalDate.of(2026, 11, 4);
        when(service.summarize(3L, from, to)).thenThrow(BusinessException.invalidField("from", "시작일은 종료일보다 늦을 수 없습니다."));

        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-05").param("to", "2026-11-04"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("from"));
    }
}
