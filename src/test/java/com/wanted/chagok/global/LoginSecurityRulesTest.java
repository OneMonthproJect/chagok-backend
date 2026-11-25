package com.wanted.chagok.global;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.account.controller.AuthController;
import com.wanted.chagok.domain.account.dto.AdminLoginResponse;
import com.wanted.chagok.domain.account.dto.AdminLoginResponse.AdminInfo;
import com.wanted.chagok.domain.account.dto.MeResponse;
import com.wanted.chagok.domain.account.service.AuthService;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.SecurityConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 로그인 API(01·02)의 공개 규칙을 <b>실제 {@link SecurityConfig} 보안 필터 체인</b>으로 확인한다 (TEST E1~E5 회귀, 08 X9·X10·X18, 12 C01).
 * Controller만 띄우고 {@link AuthService}는 가짜로 둬서 DB 없이 {@code ./gradlew build}에서 돈다. 같은 규칙을 DB와 앱 전체로 보는 것은
 * {@code EndpointGuardTest}(dbTest)다.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, ClockConfig.class})
class LoginSecurityRulesTest {

    private static final String SECRET = TestJwts.randomSecret();

    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        registry.add("chagok.jwt.secret", () -> SECRET);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService authService;

    private final Instant later = Instant.now().plusSeconds(3_600);

    private String ownerToken() {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(1, 1, "OWNER", later));
    }

    private String adminToken() {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.admin(1, later));
    }

    private String expiredToken() {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(1, 1, "OWNER", Instant.now().minusSeconds(5)));
    }

    private ResultActions login(String path, String json, String authorization) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mvc.perform(request);
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(code));
    }

    @Test
    @DisplayName("C01 01·02는 토큰 없이 부를 수 있다 (보안 필터가 막지 않고 Controller까지 간다 → 빈 본문은 400 INVALID_INPUT)")
    void loginPathsArePublic() throws Exception {
        expectError(login("/api/auth/login", "{}", null), 400, "INVALID_INPUT");
        expectError(login("/api/admin/auth/login", "{}", null), 400, "INVALID_INPUT");
    }

    @Test
    @DisplayName("01 성공 시 토큰 없이 200, LOGIN_FAILED는 401 LOGIN_FAILED (보안이 아니라 업무 응답)")
    void loginReachesService() throws Exception {
        when(authService.adminLogin(any())).thenReturn(new AdminLoginResponse("t", "Bearer", 43_200, new AdminInfo(1, "운영관리자", "ADMIN")));
        when(authService.login(any())).thenThrow(new BusinessException(ErrorCode.LOGIN_FAILED));

        login("/api/admin/auth/login", "{\"loginId\":\"admin\",\"password\":\"1234\"}", null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.admin.role").value("ADMIN"));
        expectError(login("/api/auth/login", "{\"loginId\":\"owner_a\",\"password\":\"wrong\"}", null), 401, "LOGIN_FAILED");
    }

    @Test
    @DisplayName("로그인 요청에 만료·위조·형식 오류 토큰이 붙어 와도 로그인은 막히지 않는다 (화면이 남겨 둔 만료 토큰)")
    void staleTokenOnLoginIsIgnored() throws Exception {
        expectError(login("/api/auth/login", "{}", expiredToken()), 400, "INVALID_INPUT");
        expectError(login("/api/admin/auth/login", "{}", expiredToken()), 400, "INVALID_INPUT");
        expectError(login("/api/auth/login", "{}", "Bearer not.a.jwt"), 400, "INVALID_INPUT");
        expectError(login("/api/auth/login", "{}", "Basic b3duZXJfYToxMjM0"), 400, "INVALID_INPUT");
    }

    @Test
    @DisplayName("공개는 로그인 두 경로·POST뿐: 다른 메서드·다른 경로·접두사만 같은 경로는 토큰이 필요하다 (401 AUTH_REQUIRED)")
    void onlyTheTwoLoginPostsArePublic() throws Exception {
        expectError(mvc.perform(get("/api/auth/login")), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/api/admin/auth/login")), 401, "AUTH_REQUIRED");
        expectError(login("/api/auth/login/extra", "{}", null), 401, "AUTH_REQUIRED");
        expectError(login("/api/auth/loginx", "{}", null), 401, "AUTH_REQUIRED");
        expectError(login("/api/admin/auth/other", "{}", null), 401, "AUTH_REQUIRED");
        expectError(login("/api/admin/shops", "{}", null), 401, "AUTH_REQUIRED");
    }

    @Test
    @DisplayName("03 /api/me: 토큰 없이 401(X10), 서비스 관리자 토큰은 403(X9), 사장 토큰이면 토큰의 계정으로 서비스를 부른다")
    void meRequiresShopAccountToken() throws Exception {
        when(authService.me(new LoginStaff(1, 1, StaffRole.OWNER)))
            .thenReturn(new MeResponse(1, "박사장", StaffRole.OWNER, 1, "차곡카센터"));

        expectError(mvc.perform(get("/api/me")), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/api/me").header("Authorization", adminToken())), 403, "FORBIDDEN");
        expectError(mvc.perform(get("/api/me").header("Authorization", expiredToken())), 401, "AUTH_REQUIRED");
        mvc.perform(get("/api/me").header("Authorization", ownerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.shopName").value("차곡카센터"));
        verify(authService, never()).login(any());
    }

    @Test
    @DisplayName("등록 안 된 경로는 여전히 거부 (E1): 로그인 규칙이 기본 거부를 열지 않았다")
    void unregisteredPathStillDenied() throws Exception {
        expectError(mvc.perform(get("/internal/anything")), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/internal/anything").header("Authorization", ownerToken())), 403, "FORBIDDEN");
        expectError(login("/internal/auth/login", "{}", null), 401, "AUTH_REQUIRED");
    }
}
