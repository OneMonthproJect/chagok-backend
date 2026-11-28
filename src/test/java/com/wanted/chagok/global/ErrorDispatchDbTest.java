package com.wanted.chagok.global;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanted.chagok.global.auth.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 실제 서블릿 컨테이너(RANDOM_PORT)로 오류가 {@code /error}로 넘어가는 경로를 확인한다 (#17). MockMvc는 ERROR 디스패치를 하지 않아
 * 보안 필터가 그 디스패치를 {@code anyRequest().denyAll()}로 막아 상태 코드를 403으로 바꾸는 문제를 재현하지 못한다.
 * 앱 전체가 뜨므로 MySQL이 필요하다({@code ./gradlew dbTest}). 데이터는 넣지 않는다.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorDispatchDbTest {

    @LocalServerPort
    private int port;

    @Value("${chagok.jwt.secret}")
    private String secret;

    private final HttpClient client = HttpClient.newHttpClient();

    private String ownerToken() {
        return "Bearer " + TestJwts.sign(secret, TestJwts.staff(1, 1, "OWNER", Instant.now().plusSeconds(3_600)));
    }

    private HttpResponse<String> send(String method, String rawPath, String accept, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (accept != null) {
            b.header("Accept", accept);
        }
        if (token != null) {
            b.header("Authorization", token);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("인증된 요청 + Accept: application/xml + 없는 경로 → 403이 아니라 404 NOT_FOUND JSON (재현 1, 처리기의 Content-Type 고정을 지키는 테스트이고 SecurityConfig와는 무관)")
    void acceptXmlKeepsOriginalStatus() throws Exception {
        HttpResponse<String> res = send("GET", "/api/nothing-here", "application/xml", ownerToken());

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(res.body()).contains("\"NOT_FOUND\"");
    }

    @Test
    @DisplayName("인증된 요청 + Accept: application/xml + 있는 API(36) → 403이 아니라 400 INVALID_INPUT JSON (재현 1을 실제 서블릿 컨테이너·보안 필터로, 08 0.3 406)")
    void acceptXmlOnExistingApiIsBadRequestJson() throws Exception {
        HttpResponse<String> res = send("GET", "/api/staff/assignees", "application/xml", ownerToken());

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(res.body()).contains("\"INVALID_INPUT\"");
    }

    @Test
    @DisplayName("보안 방화벽이 거부하는 경로(//)는 Tomcat이 /error로 넘기는 400이다 → 403으로 바뀌지 않고 공통 응답 모양을 지킨다 (재현 2)")
    void containerLevelErrorKeepsStatus() throws Exception {
        HttpResponse<String> res = send("GET", "/api//vehicles", "application/json", ownerToken());

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(res.body()).contains("\"INVALID_INPUT\"").doesNotContain("Exception").doesNotContain("/api//vehicles");
    }

    @Test
    @DisplayName("ERROR 디스패치를 열어도 원 요청의 인가는 가려지지 않는다: 토큰 없음 401 AUTH_REQUIRED, 서비스 관리자 토큰으로 /api 403 FORBIDDEN")
    void authorizationOfOriginalRequestIsNotMasked() throws Exception {
        HttpResponse<String> anonymous = send("GET", "/api/vehicles", "application/json", null);
        String adminToken = "Bearer " + TestJwts.sign(secret, TestJwts.admin(1, Instant.now().plusSeconds(3_600)));
        HttpResponse<String> admin = send("GET", "/api/vehicles", "application/json", adminToken);

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).contains("\"AUTH_REQUIRED\"");
        assertThat(admin.statusCode()).isEqualTo(403);
        assertThat(admin.body()).contains("\"FORBIDDEN\"");
    }

    @Test
    @DisplayName("/error를 직접 부르는 요청은 막힌다 (ERROR 디스패치만 허용, 기본 거부 원칙 유지)")
    void directErrorPathStaysDenied() throws Exception {
        HttpResponse<String> anonymous = send("GET", "/error", null, null);
        HttpResponse<String> authenticated = send("GET", "/error", "application/json", ownerToken());

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(authenticated.statusCode()).isEqualTo(403);
    }
}
