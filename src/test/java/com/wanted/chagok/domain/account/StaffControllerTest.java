package com.wanted.chagok.domain.account;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.account.api.AssigneeRef;
import com.wanted.chagok.domain.account.controller.StaffController;
import com.wanted.chagok.domain.account.service.StaffService;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** API 36 응답 모양과 shopId 출처 (08 7.1). 보안 필터 없이 MVC만 띄운다(DB 없음). 401·403은 StaffEndpointDbTest. */
class StaffControllerTest {

    private final StaffService staffService = mock(StaffService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StaffController(staffService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .build();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(LoginStaff login) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(login, null, List.of()));
    }

    @Test
    @DisplayName("36 응답: data.items[{ staffId, name, role, colorIndex }], 조회는 토큰의 shopId로 한다")
    void responseShapeAndShopFromToken() throws Exception {
        loginAs(new LoginStaff(2, 7, StaffRole.MECHANIC));
        when(staffService.findAssignees(7L)).thenReturn(List.of(
            new AssigneeRef(1, "박사장", StaffRole.OWNER, 0), new AssigneeRef(2, "김정비", StaffRole.MECHANIC, 1)));

        mvc.perform(get("/api/staff/assignees"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].staffId").value(1))
            .andExpect(jsonPath("$.data.items[0].name").value("박사장"))
            .andExpect(jsonPath("$.data.items[0].role").value("OWNER"))
            .andExpect(jsonPath("$.data.items[0].colorIndex").value(0))
            .andExpect(jsonPath("$.data.items[1].staffId").value(2))
            .andExpect(jsonPath("$.data.items[1].role").value("MECHANIC"))
            .andExpect(jsonPath("$.data.items[1].colorIndex").value(1));
        verify(staffService).findAssignees(7L);
    }

    @Test
    @DisplayName("36 요청의 shopId 쿼리는 무시한다: 정비소는 토큰으로만 정한다 (07 I-3)")
    void ignoresShopIdQuery() throws Exception {
        loginAs(new LoginStaff(1, 1, StaffRole.OWNER));
        when(staffService.findAssignees(1L)).thenReturn(List.of());

        mvc.perform(get("/api/staff/assignees").param("shopId", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(0));
        verify(staffService).findAssignees(1L);
    }
}
