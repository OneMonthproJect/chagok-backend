package com.wanted.chagok.domain.reservation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.api.ReservationAssignee;
import com.wanted.chagok.domain.reservation.controller.ReservationActionController;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationStartRequest;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * API 39 방문 접수의 요청·응답 모양과 오류 매핑 (08 39번, R21·X14). Service는 가짜, 보안 필터 없음(DB 없음).
 * 정비소는 토큰의 고정 {@code LoginStaff}(shopId 3)에서만 온다. 401·403·역할은 EndpointGuardTest(db 태그)가 본다.
 */
class ReservationStartControllerTest {

    private static final LoginStaff STAFF = new LoginStaff(7L, 3L, StaffRole.MECHANIC);

    private final ReservationService service = mock(ReservationService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReservationActionController(service))
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

    private ResultActions start(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("R21 version·연식·주행거리 → 200, status IN_PROGRESS·version +1, 정비소는 토큰의 값(3)으로 service에 전달")
    void startSuccess() throws Exception {
        when(service.start(3L, 5L, new ReservationStartRequest(0, 2019, 41000)))
            .thenReturn(new ReservationResponse(5L, ReservationStatus.IN_PROGRESS, 4L, 1,
                LocalDateTime.of(2026, 11, 24, 10, 0), null));

        start("/api/reservations/5/start", "{\"version\":0,\"modelYear\":2019,\"mileage\":41000}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservationId").value(5))
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.error").isEmpty());
        verify(service).start(3L, 5L, new ReservationStartRequest(0, 2019, 41000));
    }

    @Test
    @DisplayName("연식·주행거리는 선택 — version만 보내도 service로 간다 (차량에 값이 있을 때)")
    void startVersionOnly() throws Exception {
        when(service.start(3L, 5L, new ReservationStartRequest(0, null, null)))
            .thenReturn(new ReservationResponse(5L, ReservationStatus.IN_PROGRESS, 4L, 1, null, null));

        start("/api/reservations/5/start", "{\"version\":0}").andExpect(status().isOk());

        verify(service).start(3L, 5L, new ReservationStartRequest(0, null, null));
    }

    @Test
    @DisplayName("요청 본문의 shopId는 무시한다 (X14) — service에는 토큰의 shopId(3)만 간다")
    void bodyShopIdIsIgnored() throws Exception {
        when(service.start(3L, 5L, new ReservationStartRequest(0, null, null)))
            .thenReturn(new ReservationResponse(5L, ReservationStatus.IN_PROGRESS, 4L, 1, null, null));

        start("/api/reservations/5/start", "{\"version\":0,\"shopId\":99}").andExpect(status().isOk());

        verify(service).start(3L, 5L, new ReservationStartRequest(0, null, null));
    }

    @Test
    @DisplayName("version 없이 보냄 → 400 INVALID_INPUT + fieldErrors(version), service 호출 없음")
    void missingVersion() throws Exception {
        start("/api/reservations/5/start", "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("version"));
        verify(service, never()).start(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("version·연식이 숫자가 아님, 본문 없음 → 400 INVALID_INPUT, service 호출 없음")
    void malformedRequest() throws Exception {
        start("/api/reservations/5/start", "{\"version\":\"abc\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        start("/api/reservations/5/start", "{\"version\":0,\"modelYear\":\"이천\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/reservations/5/start").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verify(service, never()).start(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("service 오류 매핑: 상태 409 INVALID_STATUS_TRANSITION, version 409 CONCURRENT_MODIFICATION, 남의 예약 404 NOT_FOUND")
    void errorMapping() throws Exception {
        when(service.start(3L, 5L, new ReservationStartRequest(0, null, null)))
            .thenThrow(new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION));
        when(service.start(3L, 6L, new ReservationStartRequest(0, null, null)))
            .thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        when(service.start(3L, 7L, new ReservationStartRequest(0, null, null)))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        start("/api/reservations/5/start", "{\"version\":0}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
        start("/api/reservations/6/start", "{\"version\":0}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
        start("/api/reservations/7/start", "{\"version\":0}")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
