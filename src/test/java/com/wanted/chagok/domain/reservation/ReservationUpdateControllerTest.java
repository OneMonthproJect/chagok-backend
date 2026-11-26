package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.controller.ReservationActionController;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * API 19 예약 변경의 요청·응답 모양과 오류 매핑 (08 19번, R7·R9·R14, X8). Service는 가짜, 보안 필터 없음(DB 없음).
 * 로그인 계정은 고정 {@code LoginStaff}(shopId 3)를 주입한다. 401·403은 EndpointGuardTest(db 태그).
 */
class ReservationUpdateControllerTest {

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

    private ResultActions patchReservation(String path, String body) throws Exception {
        return mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("19 성공: 08 응답 모양(reservedAt 포함), 로그인 계정(shopId·역할)은 토큰의 값, 본문의 shopId·vehicleId는 읽지 않고 assigneeId는 재배정으로 읽는다 (X8)")
    void success() throws Exception {
        when(service.update(eq(STAFF), eq(3L), any(ReservationUpdateRequest.class))).thenReturn(
            new ReservationResponse(3L, ReservationStatus.RESERVED, 2L, 1, LocalDateTime.of(2026, 11, 28, 9, 0)));

        patchReservation("/api/reservations/3",
            "{\"version\":0,\"reservedAt\":\"2026-11-28T09:00:00\",\"shopId\":99,\"assigneeId\":2,\"vehicleId\":77,"
                + "\"purpose\":{\"itemIds\":[1,4],\"other\":false,\"memo\":null}}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservationId").value(3))
            .andExpect(jsonPath("$.data.status").value("RESERVED"))
            .andExpect(jsonPath("$.data.reservedAt").value("2026-11-28T09:00:00"))
            .andExpect(jsonPath("$.data.vehicleId").value(2))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.assignee").doesNotExist())
            .andExpect(jsonPath("$.error").isEmpty());

        ArgumentCaptor<ReservationUpdateRequest> sent = ArgumentCaptor.forClass(ReservationUpdateRequest.class);
        verify(service).update(eq(STAFF), eq(3L), sent.capture());
        assertThat(sent.getValue().version()).isZero();
        assertThat(sent.getValue().reservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 28, 9, 0));
        assertThat(sent.getValue().purpose().itemIds()).containsExactly(1L, 4L);
        assertThat(sent.getValue().hasChange()).isTrue();
    }

    @Test
    @DisplayName("19 재배정: assigneeId·reassignReason은 service로 그대로 전달, 담당만 보내도 바꿀 필드가 있는 요청이다 (R24)")
    void reassignFieldsArePassedThrough() throws Exception {
        when(service.update(eq(STAFF), eq(3L), any(ReservationUpdateRequest.class))).thenReturn(
            new ReservationResponse(3L, ReservationStatus.RESERVED, 2L, 1, LocalDateTime.of(2026, 11, 26, 10, 0)));

        patchReservation("/api/reservations/3", "{\"version\":0,\"assigneeId\":1,\"reassignReason\":\"고객 요청\"}")
            .andExpect(status().isOk());

        ArgumentCaptor<ReservationUpdateRequest> sent = ArgumentCaptor.forClass(ReservationUpdateRequest.class);
        verify(service).update(eq(STAFF), eq(3L), sent.capture());
        assertThat(sent.getValue().assigneeId()).isEqualTo(1L);
        assertThat(sent.getValue().reassignReason()).isEqualTo("고객 요청");
        assertThat(sent.getValue().hasChange()).isTrue();
    }

    @Test
    @DisplayName("19 reassignReason 201자 → 400 INVALID_INPUT(reassignReason), 200자는 통과, service 호출은 통과한 한 번뿐")
    void reassignReasonLength() throws Exception {
        when(service.update(eq(STAFF), eq(3L), any(ReservationUpdateRequest.class))).thenReturn(
            new ReservationResponse(3L, ReservationStatus.RESERVED, 2L, 1, LocalDateTime.of(2026, 11, 26, 10, 0)));

        patchReservation("/api/reservations/3", "{\"version\":0,\"assigneeId\":1,\"reassignReason\":\"" + "가".repeat(201) + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("reassignReason"));
        patchReservation("/api/reservations/3", "{\"version\":0,\"assigneeId\":1,\"reassignReason\":\"" + "가".repeat(200) + "\"}")
            .andExpect(status().isOk());
        verify(service, org.mockito.Mockito.times(1)).update(eq(STAFF), eq(3L), any(ReservationUpdateRequest.class));
    }

    @Test
    @DisplayName("version 누락 → 400 INVALID_INPUT(version), service 호출 없음")
    void missingVersion() throws Exception {
        patchReservation("/api/reservations/3", "{\"reservedAt\":\"2026-11-28T09:00:00\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("version"));

        verify(service, never()).update(any(), anyLong(), any());
    }

    @Test
    @DisplayName("메모 201자 → 400 INVALID_INPUT(purpose.memo), 잘못된 일시 형식·본문 없음 → 400")
    void invalidShape() throws Exception {
        patchReservation("/api/reservations/3", "{\"version\":0,\"purpose\":{\"memo\":\"" + "가".repeat(201) + "\"}}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("purpose.memo"));
        patchReservation("/api/reservations/3", "{\"version\":0,\"reservedAt\":\"모레\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(patch("/api/reservations/3").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verify(service, never()).update(any(), anyLong(), any());
    }

    @Test
    @DisplayName("예약 id가 숫자가 아님 → 400 INVALID_INPUT")
    void invalidPathId() throws Exception {
        patchReservation("/api/reservations/abc", "{\"version\":0,\"reservedAt\":\"2026-11-28T09:00:00\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("hasChange: version 밖에 바꿀 필드가 없으면 false (R14), 일시·항목·기타·메모 중 하나라도 있으면 true")
    void hasChange() {
        var none = new ReservationUpdateRequest(0, null, null, null, null);
        var emptyPurpose = new ReservationUpdateRequest(0, null,
            new com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest.Purpose(null, null, null), null, null);
        assertThat(none.hasChange()).isFalse();
        assertThat(emptyPurpose.hasChange()).isFalse();
        assertThat(new ReservationUpdateRequest(0, LocalDateTime.of(2026, 11, 28, 9, 0), null, null, null).hasChange()).isTrue();
        assertThat(new ReservationUpdateRequest(0, null,
            new com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest.Purpose(java.util.List.of(), null, null), null, null)
            .hasChange()).isTrue();
        assertThat(new ReservationUpdateRequest(0, null,
            new com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest.Purpose(null, false, null), null, null)
            .hasChange()).isTrue();
        assertThat(new ReservationUpdateRequest(0, null,
            new com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest.Purpose(null, null, ""), null, null)
            .hasChange()).isTrue();
    }

    @Test
    @DisplayName("R14 service의 INVALID_INPUT(바꿀 필드 없음) → 400 + fieldErrors, R9 INVALID_STATUS_TRANSITION → 409")
    void errorMappings() throws Exception {
        when(service.update(eq(STAFF), eq(3L), any(ReservationUpdateRequest.class)))
            .thenThrow(BusinessException.invalidField("reservedAt", "바꿀 항목을 하나 이상 보내 주세요."))
            .thenThrow(new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION));

        patchReservation("/api/reservations/3", "{\"version\":0}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("reservedAt"));
        patchReservation("/api/reservations/3", "{\"version\":0,\"reservedAt\":\"2026-11-28T09:00:00\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("R7 RESERVATION_PAST_DATE → 400, R8 CONCURRENT_MODIFICATION → 409, 유효 예약 충돌 → 409 + details.reservationId, 404")
    void moreErrorMappings() throws Exception {
        when(service.update(eq(STAFF), eq(3L), any(ReservationUpdateRequest.class)))
            .thenThrow(new BusinessException(ErrorCode.RESERVATION_PAST_DATE))
            .thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION))
            .thenThrow(new BusinessException(ErrorCode.RESERVATION_ACTIVE_EXISTS, Map.of("reservationId", 8L)))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        String body = "{\"version\":0,\"reservedAt\":\"2026-11-28T09:00:00\"}";

        patchReservation("/api/reservations/3", body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("RESERVATION_PAST_DATE"));
        patchReservation("/api/reservations/3", body).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
        patchReservation("/api/reservations/3", body).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("RESERVATION_ACTIVE_EXISTS"))
            .andExpect(jsonPath("$.error.details.reservationId").value(8));
        patchReservation("/api/reservations/3", body).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
