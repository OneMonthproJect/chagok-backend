package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.controller.ReservationController;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationListResponse;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
 * API 15 기간 조회·API 16 예약 등록의 요청·응답 모양과 오류 매핑 (08 15·16번, X8). Service는 가짜, 보안 필터 없음(DB 없음).
 * 로그인 계정은 토큰 대신 고정 {@code LoginStaff}(shopId 3, staffId 7)를 주입한다. 401·403·역할은 EndpointGuardTest(db 태그).
 * 변경(API 19)의 컨트롤러 테스트는 그 이슈에서 이 파일에 더한다.
 */
class ReservationControllerTest {

    private static final LoginStaff STAFF = new LoginStaff(7L, 3L, StaffRole.MECHANIC);

    private final ReservationService service = mock(ReservationService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReservationController(service))
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

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static final String VALID_BODY =
        "{\"vehicleId\":1,\"reservedAt\":\"2026-11-27T11:30:00\",\"purpose\":{\"itemIds\":[1],\"other\":false,\"memo\":null}}";

    // ---- API 15 ----

    @Test
    @DisplayName("15 성공: 08 응답 모양(from·to·items[]), assignee 필드 없음, shopId는 토큰의 값으로 service에 전달 (X7·X8)")
    void listSuccess() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 23);
        when(service.list(3L, day, day, null)).thenReturn(new ReservationListResponse(day, day, List.of(
            new ReservationListResponse.Item(4L, LocalDateTime.of(2026, 11, 23, 15, 0), ReservationStatus.RESERVED,
                true, 0, 10L, "55차0010", 10L, "오세훈", "010-0000-0010", "배터리"))));

        mvc.perform(get("/api/reservations").param("from", "2026-11-23").param("to", "2026-11-23").param("shopId", "99"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.from").value("2026-11-23"))
            .andExpect(jsonPath("$.data.to").value("2026-11-23"))
            .andExpect(jsonPath("$.data.items[0].reservationId").value(4))
            .andExpect(jsonPath("$.data.items[0].reservedAt").value("2026-11-23T15:00:00"))
            .andExpect(jsonPath("$.data.items[0].status").value("RESERVED"))
            .andExpect(jsonPath("$.data.items[0].needsAction").value(true))
            .andExpect(jsonPath("$.data.items[0].version").value(0))
            .andExpect(jsonPath("$.data.items[0].vehicleId").value(10))
            .andExpect(jsonPath("$.data.items[0].plateNumber").value("55차0010"))
            .andExpect(jsonPath("$.data.items[0].customerId").value(10))
            .andExpect(jsonPath("$.data.items[0].customerName").value("오세훈"))
            .andExpect(jsonPath("$.data.items[0].phone").value("010-0000-0010"))
            .andExpect(jsonPath("$.data.items[0].purposeSummary").value("배터리"))
            .andExpect(jsonPath("$.data.items[0].assignee").doesNotExist())
            .andExpect(jsonPath("$.error").isEmpty());
        verify(service).list(3L, day, day, null);
    }

    @Test
    @DisplayName("15 assigneeId 쿼리는 선택이고, 주면 그대로 service에 넘긴다 (\"내 담당만\" 보기, P0-나머지)")
    void listWithAssigneeId() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 23);
        when(service.list(3L, day, day, 2L)).thenReturn(new ReservationListResponse(day, day, List.of()));

        mvc.perform(get("/api/reservations").param("from", "2026-11-23").param("to", "2026-11-23").param("assigneeId", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());
        verify(service).list(3L, day, day, 2L);
    }

    @Test
    @DisplayName("15 assigneeId가 숫자가 아니면 400 INVALID_INPUT이고 service를 부르지 않는다")
    void listAssigneeIdMustBeNumber() throws Exception {
        mvc.perform(get("/api/reservations").param("from", "2026-11-23").param("to", "2026-11-23").param("assigneeId", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verify(service, never()).list(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("15 예약이 없으면 items는 [] (null이 아님)")
    void listEmpty() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 24);
        when(service.list(3L, day, day, null)).thenReturn(new ReservationListResponse(day, day, List.of()));

        mvc.perform(get("/api/reservations").param("from", "2026-11-24").param("to", "2026-11-24"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("15 from·to 누락·날짜 형식 오류 → 400 INVALID_INPUT, service 호출 없음")
    void listInvalidParams() throws Exception {
        mvc.perform(get("/api/reservations").param("to", "2026-11-24"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("from"));
        mvc.perform(get("/api/reservations").param("from", "2026-11-24"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("to"));
        mvc.perform(get("/api/reservations").param("from", "abc").param("to", "2026-11-24"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verify(service, never()).list(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("15 기간 규칙 위반(시작>끝·42일 초과)은 service의 400 INVALID_INPUT + fieldErrors가 그대로 나간다")
    void listPeriodRule() throws Exception {
        LocalDate from = LocalDate.of(2026, 11, 5);
        LocalDate to = LocalDate.of(2026, 11, 4);
        when(service.list(3L, from, to, null)).thenThrow(BusinessException.invalidField("from", "시작일은 종료일보다 늦을 수 없습니다."));

        mvc.perform(get("/api/reservations").param("from", "2026-11-05").param("to", "2026-11-04"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("from"));
    }

    // ---- API 16 ----

    @Test
    @DisplayName("16 성공: 08 응답 모양(assignee 없음), 등록자는 토큰의 staffId·shopId, 본문의 shopId·담당은 무시 (X8·D-51)")
    void createSuccess() throws Exception {
        when(service.create(any(Long.class), any(Long.class), any(ReservationCreateRequest.class)))
            .thenReturn(new ReservationCreateResponse(5L, ReservationStatus.RESERVED,
                LocalDateTime.of(2026, 11, 27, 11, 30), 1L, 0));

        create("{\"vehicleId\":1,\"reservedAt\":\"2026-11-27T11:30:00\",\"shopId\":99,\"assigneeId\":2,"
            + "\"purpose\":{\"itemIds\":[1],\"other\":false,\"memo\":null}}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservationId").value(5))
            .andExpect(jsonPath("$.data.status").value("RESERVED"))
            .andExpect(jsonPath("$.data.reservedAt").value("2026-11-27T11:30:00"))
            .andExpect(jsonPath("$.data.vehicleId").value(1))
            .andExpect(jsonPath("$.data.version").value(0))
            .andExpect(jsonPath("$.data.assignee").doesNotExist())
            .andExpect(jsonPath("$.error").isEmpty());

        ArgumentCaptor<ReservationCreateRequest> sent = ArgumentCaptor.forClass(ReservationCreateRequest.class);
        verify(service).create(org.mockito.ArgumentMatchers.eq(3L), org.mockito.ArgumentMatchers.eq(7L), sent.capture());
        assertThat(sent.getValue().vehicleId()).isEqualTo(1L);
        assertThat(sent.getValue().reservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 11, 30));
        assertThat(sent.getValue().purpose().itemIds()).containsExactly(1L);
        assertThat(sent.getValue().purpose().other()).isFalse();
    }

    @Test
    @DisplayName("16 필수값(vehicleId·reservedAt) 누락 → 400 INVALID_INPUT + fieldErrors, service 호출 없음")
    void createMissingRequired() throws Exception {
        create("{\"reservedAt\":\"2026-11-27T11:30:00\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("vehicleId"));
        create("{\"vehicleId\":1}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("reservedAt"));
        verify(service, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("16 메모 201자 → 400 INVALID_INPUT(purpose.memo), 200자는 통과 / 일시 형식 오류·본문 없음 → 400")
    void createInvalidShape() throws Exception {
        when(service.create(any(Long.class), any(Long.class), any(ReservationCreateRequest.class)))
            .thenReturn(new ReservationCreateResponse(5L, ReservationStatus.RESERVED,
                LocalDateTime.of(2026, 11, 27, 11, 30), 1L, 0));
        String memo201 = "가".repeat(201);
        create("{\"vehicleId\":1,\"reservedAt\":\"2026-11-27T11:30:00\",\"purpose\":{\"memo\":\"" + memo201 + "\"}}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("purpose.memo"));
        create("{\"vehicleId\":1,\"reservedAt\":\"2026-11-27T11:30:00\",\"purpose\":{\"memo\":\"" + "가".repeat(200) + "\"}}")
            .andExpect(status().isOk());
        create("{\"vehicleId\":1,\"reservedAt\":\"내일 오전\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("R3 service의 RESERVATION_ACTIVE_EXISTS → 409 + details.reservationId (08 16번 예시 모양)")
    void activeExists() throws Exception {
        when(service.create(any(Long.class), any(Long.class), any(ReservationCreateRequest.class)))
            .thenThrow(new BusinessException(ErrorCode.RESERVATION_ACTIVE_EXISTS, Map.of("reservationId", 3L)));

        create(VALID_BODY)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code").value("RESERVATION_ACTIVE_EXISTS"))
            .andExpect(jsonPath("$.error.fieldErrors").isEmpty())
            .andExpect(jsonPath("$.error.details.reservationId").value(3));
    }

    @Test
    @DisplayName("R2 RESERVATION_PAST_DATE → 400, X4 다른 정비소 차량 NOT_FOUND → 404, X11·R1 INVALID_INPUT → 400 + fieldErrors")
    void errorMappings() throws Exception {
        when(service.create(any(Long.class), any(Long.class), any(ReservationCreateRequest.class)))
            .thenThrow(new BusinessException(ErrorCode.RESERVATION_PAST_DATE))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND))
            .thenThrow(BusinessException.invalidField("purpose.itemIds", "사용할 수 없는 항목입니다."));

        create(VALID_BODY).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("RESERVATION_PAST_DATE"));
        create(VALID_BODY).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        create(VALID_BODY).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("purpose.itemIds"));
    }
}
