package com.wanted.chagok.domain.reservation;

import static org.mockito.ArgumentMatchers.anyInt;
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
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * API 18 방문 완료의 요청·응답 모양과 오류 매핑 (08 18번, R13). Service는 가짜, 보안 필터 없음(DB 없음).
 * 로그인 계정은 토큰 대신 고정 {@code LoginStaff}(shopId 3)를 주입해 "정비소는 토큰에서만 온다"를 확인한다.
 * 401·403·역할은 EndpointGuardTest(db 태그)가 본다.
 */
class ReservationActionControllerTest {

    private static final LoginStaff STAFF = new LoginStaff(7L, 3L, StaffRole.OWNER);

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

    private org.springframework.test.web.servlet.ResultActions complete(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("R13 맞는 version → 200, 08 18번 응답(reservedAt 없음), shopId는 토큰의 값으로 service에 전달")
    void completeSuccess() throws Exception {
        when(service.complete(3L, 5L, 0))
            .thenReturn(new ReservationResponse(5L, ReservationStatus.COMPLETED, 4L, 1, null, null));

        complete("/api/reservations/5/complete", "{\"version\":0}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservationId").value(5))
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.vehicleId").value(4))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.reservedAt").doesNotExist()) // 18번 응답에는 일시가 없다
            .andExpect(jsonPath("$.error").isEmpty());
        verify(service).complete(3L, 5L, 0);
    }

    @Test
    @DisplayName("요청 본문의 shopId는 무시한다 (X8) — service에는 토큰의 shopId(3)만 간다")
    void bodyShopIdIsIgnored() throws Exception {
        when(service.complete(3L, 5L, 0))
            .thenReturn(new ReservationResponse(5L, ReservationStatus.COMPLETED, 4L, 1, null, null));

        complete("/api/reservations/5/complete", "{\"version\":0,\"shopId\":99}").andExpect(status().isOk());

        verify(service).complete(3L, 5L, 0);
    }

    @Test
    @DisplayName("R13 version 없이 보냄 → 400 INVALID_INPUT + fieldErrors(version), service 호출 없음")
    void missingVersion() throws Exception {
        complete("/api/reservations/5/complete", "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("version"));
        verify(service, never()).complete(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("version이 숫자가 아님 → 400 INVALID_INPUT, service 호출 없음")
    void invalidVersion() throws Exception {
        complete("/api/reservations/5/complete", "{\"version\":\"abc\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verify(service, never()).complete(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("음수 version은 형식 오류가 아니다(08은 서버 값과 다르면 409) — 그대로 service로 가서 409 CONCURRENT_MODIFICATION")
    void negativeVersionIsJustAMismatch() throws Exception {
        when(service.complete(3L, 5L, -1)).thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));

        complete("/api/reservations/5/complete", "{\"version\":-1}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
        verify(service).complete(3L, 5L, -1);
    }

    @Test
    @DisplayName("본문 없음·예약 id가 숫자가 아님 → 400 INVALID_INPUT")
    void malformedRequest() throws Exception {
        mvc.perform(post("/api/reservations/5/complete").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        complete("/api/reservations/abc/complete", "{\"version\":0}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("R5·R10 service의 INVALID_STATUS_TRANSITION → 409, 08 0.2 오류 모양")
    void invalidTransition() throws Exception {
        when(service.complete(3L, 5L, 0)).thenThrow(new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION));

        complete("/api/reservations/5/complete", "{\"version\":0}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
            .andExpect(jsonPath("$.error.fieldErrors").isEmpty())
            .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    @DisplayName("R10·R12 CONCURRENT_MODIFICATION → 409, 다른 정비소·없는 예약 NOT_FOUND → 404")
    void concurrentAndNotFound() throws Exception {
        when(service.complete(3L, 5L, 0)).thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        when(service.complete(3L, 6L, 0)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        complete("/api/reservations/5/complete", "{\"version\":0}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
        complete("/api/reservations/6/complete", "{\"version\":0}")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- API 20 취소 · 21 노쇼 (08 20·21번) ----

    @Test
    @DisplayName("20 취소: 맞는 version → 200, 08 20번 응답(CANCELED, reservedAt 없음), shopId는 토큰의 값")
    void cancelSuccess() throws Exception {
        when(service.cancel(3L, 5L, 0))
            .thenReturn(new ReservationResponse(5L, ReservationStatus.CANCELED, 4L, 1, null, null));

        complete("/api/reservations/5/cancel", "{\"version\":0,\"shopId\":99}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservationId").value(5))
            .andExpect(jsonPath("$.data.status").value("CANCELED"))
            .andExpect(jsonPath("$.data.vehicleId").value(4))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.reservedAt").doesNotExist())
            .andExpect(jsonPath("$.error").isEmpty());
        verify(service).cancel(3L, 5L, 0);
    }

    @Test
    @DisplayName("21 노쇼: 맞는 version → 200, 08 21번 응답(NO_SHOW), shopId는 토큰의 값")
    void noShowSuccess() throws Exception {
        when(service.markNoShow(3L, 5L, 0))
            .thenReturn(new ReservationResponse(5L, ReservationStatus.NO_SHOW, 10L, 1, null, null));

        complete("/api/reservations/5/no-show", "{\"version\":0}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("NO_SHOW"))
            .andExpect(jsonPath("$.data.vehicleId").value(10))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.reservedAt").doesNotExist());
        verify(service).markNoShow(3L, 5L, 0);
    }

    @Test
    @DisplayName("R13 20·21 version 없이 보냄 → 400 INVALID_INPUT + fieldErrors(version), service 호출 없음")
    void cancelAndNoShowMissingVersion() throws Exception {
        for (String path : new String[] {"/api/reservations/5/cancel", "/api/reservations/5/no-show"}) {
            complete(path, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("version"));
        }
        verify(service, never()).cancel(anyLong(), anyLong(), anyInt());
        verify(service, never()).markNoShow(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("20·21 service 오류 매핑: INVALID_STATUS_TRANSITION·CONCURRENT_MODIFICATION → 409, NOT_FOUND(X12) → 404")
    void cancelAndNoShowErrors() throws Exception {
        when(service.cancel(3L, 5L, 0)).thenThrow(new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION));
        when(service.markNoShow(3L, 5L, 0)).thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        when(service.cancel(3L, 6L, 0)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        when(service.markNoShow(3L, 6L, 0)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        complete("/api/reservations/5/cancel", "{\"version\":0}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
        complete("/api/reservations/5/no-show", "{\"version\":0}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
        complete("/api/reservations/6/cancel", "{\"version\":0}")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        complete("/api/reservations/6/no-show", "{\"version\":0}")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
