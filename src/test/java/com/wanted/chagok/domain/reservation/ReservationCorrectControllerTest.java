package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.controller.ReservationActionController;
import com.wanted.chagok.domain.reservation.controller.ReservationController;
import com.wanted.chagok.domain.reservation.dto.ReservationCorrectRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationRevisionListResponse;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.entity.RevisionKind;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
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
 * API 40 지난 예약 정정·API 41 예약 이력 조회의 요청·응답 모양과 오류 매핑 (08 7.1 40·41번, R25~R29, X14). Service는 가짜, 보안 필터 없음(DB 없음).
 * 로그인 계정은 토큰 대신 고정 {@code LoginStaff}(shopId 3)를 주입한다. 401·403(역할)은 EndpointGuardTest·DB 테스트가 본다.
 */
class ReservationCorrectControllerTest {

    private static final LoginStaff STAFF = new LoginStaff(7L, 3L, StaffRole.OWNER);

    private final ReservationService service = mock(ReservationService.class);

    private final HandlerMethodArgumentResolver staffResolver = new HandlerMethodArgumentResolver() {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentStaff.class);
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            return STAFF;
        }
    };

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReservationActionController(service), new ReservationController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(staffResolver)
        .build();

    private ResultActions correct(long id, String body) throws Exception {
        return mvc.perform(post("/api/reservations/" + id + "/correct").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("40 성공: 18·20·21과 같은 응답 모양(reservedAt 없음), 로그인 계정은 토큰의 값, 요청 필드는 그대로 service로")
    void correctSuccess() throws Exception {
        when(service.correct(eq(STAFF), eq(4L), any(ReservationCorrectRequest.class)))
            .thenReturn(new ReservationResponse(4L, ReservationStatus.CANCELED, 10L, 2, null, null));

        correct(4, "{\"version\":1,\"status\":\"CANCELED\",\"reason\":\"고객이 전날 전화로 취소함\",\"shopId\":99}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservationId").value(4))
            .andExpect(jsonPath("$.data.status").value("CANCELED"))
            .andExpect(jsonPath("$.data.vehicleId").value(10))
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.reservedAt").doesNotExist())
            .andExpect(jsonPath("$.error").isEmpty());

        ArgumentCaptor<ReservationCorrectRequest> sent = ArgumentCaptor.forClass(ReservationCorrectRequest.class);
        verify(service).correct(eq(STAFF), eq(4L), sent.capture());
        assertThat(sent.getValue().version()).isEqualTo(1);
        assertThat(sent.getValue().status()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(sent.getValue().purposeMemo()).isNull();
        assertThat(sent.getValue().reason()).isEqualTo("고객이 전날 전화로 취소함");
    }

    @Test
    @DisplayName("40 메모 정정 요청: purposeMemo는 빈 문자열도 그대로 전달된다 (비우기)")
    void memoPassedThrough() throws Exception {
        when(service.correct(eq(STAFF), eq(4L), any(ReservationCorrectRequest.class)))
            .thenReturn(new ReservationResponse(4L, ReservationStatus.COMPLETED, 10L, 3, null, null));

        correct(4, "{\"version\":2,\"purposeMemo\":\"\"}").andExpect(status().isOk());

        ArgumentCaptor<ReservationCorrectRequest> sent = ArgumentCaptor.forClass(ReservationCorrectRequest.class);
        verify(service).correct(eq(STAFF), eq(4L), sent.capture());
        assertThat(sent.getValue().purposeMemo()).isEmpty();
        assertThat(sent.getValue().status()).isNull();
    }

    @Test
    @DisplayName("40 version 없음·알 수 없는 status 값·메모/사유 201자 → 400 INVALID_INPUT + fieldErrors, service 호출 없음")
    void invalidRequests() throws Exception {
        correct(4, "{\"status\":\"CANCELED\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("version"));
        correct(4, "{\"version\":1,\"status\":\"DONE\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        correct(4, "{\"version\":1,\"purposeMemo\":\"" + "가".repeat(201) + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("purposeMemo"));
        correct(4, "{\"version\":1,\"status\":\"CANCELED\",\"reason\":\"" + "가".repeat(201) + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("reason"));
        verify(service, never()).correct(any(), anyLong(), any());
    }

    @Test
    @DisplayName("40 service 오류 매핑: 404, 409(상태·version), 403(FORBIDDEN·EDIT_PERIOD_EXPIRED)")
    void errorMapping() throws Exception {
        when(service.correct(eq(STAFF), eq(1L), any())).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        when(service.correct(eq(STAFF), eq(2L), any())).thenThrow(new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION));
        when(service.correct(eq(STAFF), eq(3L), any())).thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        when(service.correct(eq(STAFF), eq(4L), any())).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        when(service.correct(eq(STAFF), eq(5L), any())).thenThrow(new BusinessException(ErrorCode.EDIT_PERIOD_EXPIRED));
        String body = "{\"version\":1,\"status\":\"CANCELED\"}";

        correct(1, body).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        correct(2, body).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
        correct(3, body).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
        correct(4, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        correct(5, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("EDIT_PERIOD_EXPIRED"));
    }

    @Test
    @DisplayName("41 성공: 08 예시 모양 — null인 reason·before도 생략하지 않고 null로, 담당 변경은 {staffId,name}, 정비소는 토큰의 값(3)")
    void revisionsShape() throws Exception {
        when(service.revisions(3L, 4L)).thenReturn(new ReservationRevisionListResponse(List.of(
            new ReservationRevisionListResponse.Item(RevisionKind.ASSIGNEE, LocalDateTime.of(2026, 11, 21, 16, 20),
                new ReservationRevisionListResponse.Person(1L, "박사장"), null, List.of(new ReservationRevisionListResponse.Change(
                "assignee", new ReservationRevisionListResponse.Person(2L, "김정비"), new ReservationRevisionListResponse.Person(1L, "박사장")))),
            new ReservationRevisionListResponse.Item(RevisionKind.MEMO, LocalDateTime.of(2026, 11, 25, 9, 10),
                new ReservationRevisionListResponse.Person(1L, "박사장"), "고객 전화",
                List.of(new ReservationRevisionListResponse.Change("purposeMemo", null, "배터리 교체"))))));

        mvc.perform(get("/api/reservations/4/revisions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items[0].kind").value("ASSIGNEE"))
            .andExpect(jsonPath("$.data.items[0].changedAt").value("2026-11-21T16:20:00"))
            .andExpect(jsonPath("$.data.items[0].changedBy.staffId").value(1))
            .andExpect(jsonPath("$.data.items[0].changedBy.name").value("박사장"))
            .andExpect(jsonPath("$.data.items[0].reason").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].changes[0].field").value("assignee"))
            .andExpect(jsonPath("$.data.items[0].changes[0].before.name").value("김정비"))
            .andExpect(jsonPath("$.data.items[0].changes[0].after.staffId").value(1))
            .andExpect(jsonPath("$.data.items[1].kind").value("MEMO"))
            .andExpect(jsonPath("$.data.items[1].changes[0].field").value("purposeMemo"))
            .andExpect(jsonPath("$.data.items[1].changes[0].before").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[1].changes[0].after").value("배터리 교체"));
        verify(service).revisions(3L, 4L);
    }

    @Test
    @DisplayName("41 이력이 없으면 items는 [] (null이 아님), 남의 예약 404, 예약 id가 숫자가 아니면 400")
    void revisionsEmptyAndErrors() throws Exception {
        when(service.revisions(3L, 4L)).thenReturn(new ReservationRevisionListResponse(List.of()));
        when(service.revisions(3L, 9L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mvc.perform(get("/api/reservations/4/revisions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/reservations/9/revisions"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/reservations/abc/revisions"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
}
