package com.wanted.chagok.screen.booking;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.cycle.api.BaseRecord;
import com.wanted.chagok.domain.cycle.api.CycleNote;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.maintenance.api.VisitRef;
import com.wanted.chagok.domain.reservation.api.ReservationAssignee;
import com.wanted.chagok.global.auth.CurrentStaff;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import com.wanted.chagok.screen.booking.controller.PanelController;
import com.wanted.chagok.screen.booking.dto.PanelResponse;
import com.wanted.chagok.screen.booking.service.PanelService;
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
 * API 17 예약 상세 패널의 응답 모양과 오류 매핑 (08 17번, X3). Service는 가짜, 보안 필터 없음(DB 없음).
 * 로그인 계정은 고정 {@code LoginStaff}(shopId 3)를 주입한다. 401·403은 EndpointGuardTest(db 태그).
 */
class PanelControllerTest {

    private static final LoginStaff STAFF = new LoginStaff(7L, 3L, StaffRole.MECHANIC);

    private final PanelService service = mock(PanelService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PanelController(service))
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

    private static PanelResponse panel() {
        return new PanelResponse(
            new PanelResponse.ReservationBlock(4L, LocalDateTime.of(2026, 11, 23, 15, 0), "RESERVED", true, 0,
                new PanelResponse.Purpose(List.of(new PanelResponse.PurposeItem(6L, "배터리")), false, null),
                new ReservationAssignee(1L, "박사장", 0)),
            new PanelResponse.CustomerBlock(10L, "오세훈", "010-0000-0010", true),
            new PanelResponse.VehicleBlock(10L, "55차0010", "쏘나타", 2018, 97000),
            List.of(new VisitRef("5eed-15", LocalDate.of(2026, 10, 24), null, "김정비", List.of(
                new VisitRef.VisitRecord(16L, 1L, "엔진오일", "REPLACE", LocalDate.of(2026, 10, 24), null)))),
            3L,
            List.of(new PanelResponse.ItemStatusBlock(6L, "BATTERY", "배터리", ItemStatus.SOON, null,
                new BaseRecord(LocalDate.of(2023, 12, 14), null, "REPLACE"), LocalDate.of(2026, 12, 14), null,
                null, new CycleNote(LocalDate.of(2026, 10, 24), "앞 타이어 마모"), true)));
    }

    @Test
    @DisplayName("17 성공: 08 응답 모양(reservation.assignee 포함), shopId는 토큰의 값")
    void success() throws Exception {
        when(service.getPanel(3L, 4L)).thenReturn(panel());

        mvc.perform(get("/api/reservations/4/panel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservation.reservationId").value(4))
            .andExpect(jsonPath("$.data.reservation.reservedAt").value("2026-11-23T15:00:00"))
            .andExpect(jsonPath("$.data.reservation.needsAction").value(true))
            .andExpect(jsonPath("$.data.reservation.version").value(0))
            .andExpect(jsonPath("$.data.reservation.purpose.items[0].name").value("배터리"))
            .andExpect(jsonPath("$.data.reservation.purpose.memo").doesNotExist())
            .andExpect(jsonPath("$.data.reservation.assignee.staffId").value(1))
            .andExpect(jsonPath("$.data.reservation.assignee.name").value("박사장"))
            .andExpect(jsonPath("$.data.reservation.assignee.colorIndex").value(0))
            .andExpect(jsonPath("$.data.customer.phone").value("010-0000-0010"))
            .andExpect(jsonPath("$.data.customer.consent").value(true))
            .andExpect(jsonPath("$.data.vehicle.latestMileage").value(97000))
            .andExpect(jsonPath("$.data.recentVisits[0].visitKey").value("5eed-15"))
            .andExpect(jsonPath("$.data.recentVisits[0].records[0].itemName").value("엔진오일"))
            .andExpect(jsonPath("$.data.totalVisitCount").value(3))
            .andExpect(jsonPath("$.data.itemStatuses[0].status").value("SOON"))
            .andExpect(jsonPath("$.data.itemStatuses[0].baseRecord.date").value("2023-12-14"))
            .andExpect(jsonPath("$.data.itemStatuses[0].nextDueDate").value("2026-12-14"))
            .andExpect(jsonPath("$.data.itemStatuses[0].lastDecline").isEmpty())
            .andExpect(jsonPath("$.data.itemStatuses[0].lastCaution.date").value("2026-10-24"))
            .andExpect(jsonPath("$.data.itemStatuses[0].lastCaution.memo").value("앞 타이어 마모"))
            .andExpect(jsonPath("$.data.itemStatuses[0].highlighted").value(true));

        verify(service).getPanel(3L, 4L);
    }

    @Test
    @DisplayName("X3 없거나 다른 정비소 예약 → 404 NOT_FOUND 08 오류 모양")
    void notFound() throws Exception {
        when(service.getPanel(anyLong(), anyLong())).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mvc.perform(get("/api/reservations/99/panel"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("예약 번호가 숫자가 아니면 → 400 INVALID_INPUT")
    void invalidId() throws Exception {
        mvc.perform(get("/api/reservations/abc/panel"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
}
