package com.wanted.chagok.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.account.controller.AuthController;
import com.wanted.chagok.domain.account.dto.AdminLoginResponse;
import com.wanted.chagok.domain.account.dto.AdminLoginResponse.AdminInfo;
import com.wanted.chagok.domain.account.dto.LoginRequest;
import com.wanted.chagok.domain.account.dto.LoginResponse;
import com.wanted.chagok.domain.account.dto.MeResponse;
import com.wanted.chagok.domain.account.service.AuthService;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * API 01~03의 요청·응답 모양이 08과 같은지 본다 (08 2장 JSON 예시). 보안 필터 없이 MVC만 띄운다(DB 없음).
 * 보안 필터의 공개 경로·401·403은 {@code EndpointGuardTest}에서 본다.
 */
class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .build();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ---------- 01 ----------

    @Test
    @DisplayName("01 성공 응답이 08 예시와 같은 모양 (accessToken·tokenType·expiresIn·staff)")
    void loginResponseShape() throws Exception {
        MeResponse staff = new MeResponse(1, "박사장", StaffRole.OWNER, 1, "차곡카센터");
        when(authService.login(new LoginRequest("owner_a", "1234")))
            .thenReturn(new LoginResponse("token-value", "Bearer", 43_200, staff));

        postJson("/api/auth/login", "{\"loginId\":\"owner_a\",\"password\":\"1234\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.accessToken").value("token-value"))
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.data.expiresIn").value(43_200))
            .andExpect(jsonPath("$.data.staff.staffId").value(1))
            .andExpect(jsonPath("$.data.staff.name").value("박사장"))
            .andExpect(jsonPath("$.data.staff.role").value("OWNER"))
            .andExpect(jsonPath("$.data.staff.shopId").value(1))
            .andExpect(jsonPath("$.data.staff.shopName").value("차곡카센터"));
    }

    @Test
    @DisplayName("01 LOGIN_FAILED → 401, 08 오류 모양 (아이디 없음·비밀번호 틀림을 구분하지 않는 문구)")
    void loginFailed() throws Exception {
        when(authService.login(any())).thenThrow(new BusinessException(ErrorCode.LOGIN_FAILED));

        postJson("/api/auth/login", "{\"loginId\":\"owner_a\",\"password\":\"wrong\"}")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"))
            .andExpect(jsonPath("$.error.message").value("아이디 또는 비밀번호가 맞지 않습니다."))
            .andExpect(jsonPath("$.error.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("01 입력 검증: 빈 값·누락·상한 초과(아이디 51자, 비밀번호 101자) → 400 INVALID_INPUT + fieldErrors, 서비스는 부르지 않는다")
    void loginValidation() throws Exception {
        postJson("/api/auth/login", "{\"loginId\":\"\",\"password\":\"1234\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("loginId"));
        postJson("/api/auth/login", "{\"loginId\":\"owner_a\",\"password\":\"\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"));
        postJson("/api/auth/login", "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        postJson("/api/auth/login", "{\"loginId\":\"" + "a".repeat(51) + "\",\"password\":\"1234\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("loginId"));
        postJson("/api/auth/login", "{\"loginId\":\"owner_a\",\"password\":\"" + "a".repeat(101) + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"));
        postJson("/api/auth/login", "{broken")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verify(authService, never()).login(any());
    }

    @Test
    @DisplayName("01 경계: 아이디 50자·비밀번호 100자, 짧은 시연 비밀번호 1234는 검증을 통과한다 (신규 비밀번호 규칙 미적용)")
    void loginBoundariesPassValidation() throws Exception {
        when(authService.login(any())).thenThrow(new BusinessException(ErrorCode.LOGIN_FAILED));

        postJson("/api/auth/login", "{\"loginId\":\"" + "a".repeat(50) + "\",\"password\":\"" + "b".repeat(100) + "\"}")
            .andExpect(status().isUnauthorized());
        postJson("/api/auth/login", "{\"loginId\":\"a\",\"password\":\"1\"}")
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("검증 실패 응답에 입력한 비밀번호 값이 없다")
    void passwordIsNotEchoedOnValidationError() throws Exception {
        String password = "p".repeat(101);

        String body = postJson("/api/auth/login", "{\"loginId\":\"owner_a\",\"password\":\"" + password + "\"}")
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(password);
    }

    // ---------- 02 ----------

    @Test
    @DisplayName("02 성공 응답이 08 예시와 같은 모양 (admin.role = ADMIN, staff 없음)")
    void adminLoginResponseShape() throws Exception {
        when(authService.adminLogin(new LoginRequest("admin", "1234")))
            .thenReturn(new AdminLoginResponse("admin-token", "Bearer", 43_200, new AdminInfo(1, "운영관리자", "ADMIN")));

        postJson("/api/admin/auth/login", "{\"loginId\":\"admin\",\"password\":\"1234\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("admin-token"))
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.data.expiresIn").value(43_200))
            .andExpect(jsonPath("$.data.admin.adminId").value(1))
            .andExpect(jsonPath("$.data.admin.name").value("운영관리자"))
            .andExpect(jsonPath("$.data.admin.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.staff").doesNotExist());
    }

    @Test
    @DisplayName("02 LOGIN_FAILED → 401, 입력 검증은 01과 같다")
    void adminLoginFailureAndValidation() throws Exception {
        when(authService.adminLogin(any())).thenThrow(new BusinessException(ErrorCode.LOGIN_FAILED));

        postJson("/api/admin/auth/login", "{\"loginId\":\"admin\",\"password\":\"wrong\"}")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
        postJson("/api/admin/auth/login", "{\"loginId\":\"\",\"password\":\"1234\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("loginId"));
    }

    // ---------- 03 ----------

    @Test
    @DisplayName("03 내 계정: 토큰의 로그인 정보(@CurrentStaff)로 부르고 08 예시와 같은 모양으로 답한다")
    void meResponseShape() throws Exception {
        LoginStaff login = new LoginStaff(1, 1, StaffRole.OWNER);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(login, null, List.of()));
        when(authService.me(login)).thenReturn(new MeResponse(1, "박사장", StaffRole.OWNER, 1, "차곡카센터"));

        mvc.perform(get("/api/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.staffId").value(1))
            .andExpect(jsonPath("$.data.name").value("박사장"))
            .andExpect(jsonPath("$.data.role").value("OWNER"))
            .andExpect(jsonPath("$.data.shopId").value(1))
            .andExpect(jsonPath("$.data.shopName").value("차곡카센터"));
    }

    // ---------- 비밀번호·토큰 노출 방지 ----------

    @Test
    @DisplayName("요청·응답 객체의 toString에 비밀번호·토큰이 없다 (로그·예외 메시지로 새지 않게)")
    void dtoToStringHidesSecrets() {
        LoginRequest request = new LoginRequest("owner_a", "super-secret-pw");
        LoginResponse login = new LoginResponse("staff-token-value", "Bearer", 43_200,
            new MeResponse(1, "박사장", StaffRole.OWNER, 1, "차곡카센터"));
        AdminLoginResponse admin = new AdminLoginResponse("admin-token-value", "Bearer", 43_200, new AdminInfo(1, "운영관리자", "ADMIN"));

        assertThat(request.toString()).doesNotContain("super-secret-pw").contains("owner_a");
        assertThat(login.toString()).doesNotContain("staff-token-value");
        assertThat(admin.toString()).doesNotContain("admin-token-value");
    }
}
