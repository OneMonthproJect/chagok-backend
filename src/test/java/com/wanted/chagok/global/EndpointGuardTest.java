package com.wanted.chagok.global;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.global.auth.TestJwts;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 실제 보안 필터 체인·actuator로 URL 권한을 확인한다 (12 9장 ①~⑤, TEST E1~E5, 08 X9·X10, 06 5장).
 * 앱 전체가 뜨므로 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 토큰은 실행 중인 앱의 {@code chagok.jwt.secret}으로 서명한다.
 *
 * <p>정비소 소유 일치(X1~X8)는 각 도메인 이슈에서 본다. 아직 Controller가 없으므로 "보안을 통과한 요청"은 404 {@code NOT_FOUND}로 확인한다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
class EndpointGuardTest {

    @Autowired
    private MockMvc mvc;

    @Value("${chagok.jwt.secret}")
    private String secret;

    private final Instant later = Instant.now().plusSeconds(3_600);

    private String ownerToken() {
        return "Bearer " + TestJwts.sign(secret, TestJwts.staff(1, 1, "OWNER", later));
    }

    private String mechanicToken() {
        return "Bearer " + TestJwts.sign(secret, TestJwts.staff(2, 1, "MECHANIC", later));
    }

    private String adminToken() {
        return "Bearer " + TestJwts.sign(secret, TestJwts.admin(1, later));
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.error.message").isNotEmpty())
            .andExpect(jsonPath("$.error.fieldErrors").isArray())
            .andExpect(jsonPath("$.error.fieldErrors").isEmpty())
            .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    @DisplayName("E1 등록 안 된 경로 → 거부: 토큰 없으면 401, 어떤 역할 토큰이어도 403 (denyAll)")
    void e1_unregisteredPathIsDenied() throws Exception {
        expectError(mvc.perform(get("/internal/anything")), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/internal/anything").header("Authorization", ownerToken())), 403, "FORBIDDEN");
        expectError(mvc.perform(get("/internal/anything").header("Authorization", adminToken())), 403, "FORBIDDEN");
        expectError(mvc.perform(get("/actuator/env").header("Authorization", ownerToken())), 403, "FORBIDDEN");
        expectError(mvc.perform(post("/actuator/health")), 401, "AUTH_REQUIRED");
    }

    @Test
    @DisplayName("E2 토큰 없이 /api/**·/api/admin/** → 401 AUTH_REQUIRED (08 X10)")
    void e2_noTokenIsUnauthorized() throws Exception {
        expectError(mvc.perform(get("/api/home")), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/api/admin/shops")), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/api/home").header("Authorization", "Basic b3duZXJfYToxMjM0")), 401, "AUTH_REQUIRED");
    }

    @Test
    @DisplayName("만료·위조·형식 오류 토큰 → 401 AUTH_REQUIRED (08 0.3 토큰 없음·만료·위조)")
    void invalidTokensAreUnauthorized() throws Exception {
        String expired = "Bearer " + TestJwts.sign(secret, TestJwts.staff(1, 1, "OWNER", Instant.now().minusSeconds(5)));
        String forged = "Bearer " + TestJwts.sign(TestJwts.randomSecret(), TestJwts.staff(1, 1, "OWNER", later));
        String noShop = "Bearer " + TestJwts.sign(secret,
            TestJwts.staff(1, 1, "OWNER", later).claim("shopId", null));
        expectError(mvc.perform(get("/api/home").header("Authorization", expired)), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/api/home").header("Authorization", forged)), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/api/home").header("Authorization", "Bearer not.a.jwt")), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/api/home").header("Authorization", noShop)), 401, "AUTH_REQUIRED");
    }

    @Test
    @DisplayName("E3 서비스 관리자 토큰으로 /api/** → 403 FORBIDDEN (08 X9·X16)")
    void e3_adminCannotUseShopApi() throws Exception {
        expectError(mvc.perform(get("/api/customers").header("Authorization", adminToken())), 403, "FORBIDDEN");
        expectError(mvc.perform(get("/api/home").header("Authorization", adminToken())), 403, "FORBIDDEN");
        expectError(mvc.perform(get("/api/targets").header("Authorization", adminToken())), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("E4 사장·정비사 토큰으로 /api/admin/** → 403 FORBIDDEN (06 4장)")
    void e4_shopAccountCannotUseAdminApi() throws Exception {
        expectError(mvc.perform(get("/api/admin/shops").header("Authorization", ownerToken())), 403, "FORBIDDEN");
        expectError(mvc.perform(get("/api/admin/shops").header("Authorization", mechanicToken())), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("E5 /actuator/health만 인증 없이 200")
    void e5_healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    private ResultActions postJson(String path, String json, String authorization) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mvc.perform(request);
    }

    /**
     * 보안 필터를 지나 Controller의 입력 검증에서 막힌 요청: 400 {@code INVALID_INPUT}이고 어느 입력이 틀렸는지
     * {@code fieldErrors}가 채워진다. 보안 오류용 {@link #expectError}는 {@code fieldErrors}가 비어 있어야 하므로 쓰지 않는다.
     */
    private static void expectValidationError(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors").isNotEmpty());
    }

    @Test
    @DisplayName("C01 로그인 01·02는 토큰 없이 부를 수 있다: 보안이 막지 않고 Controller까지 가서 빈 본문은 400, 없는 계정은 401 LOGIN_FAILED")
    void c01_loginPathsArePublic() throws Exception {
        expectValidationError(postJson("/api/auth/login", "{}", null));
        expectValidationError(postJson("/api/admin/auth/login", "{}", null));
        expectError(postJson("/api/auth/login", "{\"loginId\":\"no_such_login_id\",\"password\":\"x\"}", null), 401, "LOGIN_FAILED");
        expectError(postJson("/api/admin/auth/login", "{\"loginId\":\"no_such_login_id\",\"password\":\"x\"}", null), 401, "LOGIN_FAILED");
    }

    @Test
    @DisplayName("로그인 요청에 만료·위조 토큰이 붙어 와도 로그인은 막히지 않는다 (401 AUTH_REQUIRED가 아니라 업무 응답)")
    void staleTokenOnLoginIsIgnored() throws Exception {
        String expired = "Bearer " + TestJwts.sign(secret, TestJwts.staff(1, 1, "OWNER", Instant.now().minusSeconds(5)));
        expectValidationError(postJson("/api/auth/login", "{}", expired));
        expectValidationError(postJson("/api/admin/auth/login", "{}", expired));
        expectValidationError(postJson("/api/auth/login", "{}", "Bearer not.a.jwt"));
    }

    @Test
    @DisplayName("공개는 로그인 두 경로·POST뿐: 다른 메서드·다른 경로는 토큰이 필요하다 (401 AUTH_REQUIRED)")
    void onlyTheTwoLoginPostsArePublic() throws Exception {
        expectError(mvc.perform(get("/api/auth/login")), 401, "AUTH_REQUIRED");
        expectError(postJson("/api/auth/login/extra", "{}", null), 401, "AUTH_REQUIRED");
        expectError(postJson("/api/admin/auth/other", "{}", null), 401, "AUTH_REQUIRED");
        expectError(mvc.perform(get("/api/me")), 401, "AUTH_REQUIRED");
    }

    @Test
    @DisplayName("X9 서비스 관리자 토큰으로 03 /api/me → 403 FORBIDDEN")
    void x9_adminCannotCallMe() throws Exception {
        expectError(mvc.perform(get("/api/me").header("Authorization", adminToken())), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("올바른 역할은 보안을 통과한다 (처리기가 없는 경로라 401·403이 아니라 404 NOT_FOUND)")
    void rightRolePassesSecurity() throws Exception {
        // 구현된 API(예: /api/home)는 실제 응답을 내므로 "보안은 통과했지만 처리기가 없다"를 보는 데 쓸 수 없다. 앞으로도 만들지 않을 경로를 쓴다
        expectError(mvc.perform(get("/api/no-such-endpoint").header("Authorization", ownerToken())), 404, "NOT_FOUND");
        expectError(mvc.perform(get("/api/no-such-endpoint").header("Authorization", mechanicToken())), 404, "NOT_FOUND");
        expectError(mvc.perform(get("/api/admin/shops").header("Authorization", adminToken())), 404, "NOT_FOUND");
    }
}
