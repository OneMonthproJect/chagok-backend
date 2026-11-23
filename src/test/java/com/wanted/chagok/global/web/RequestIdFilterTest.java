package com.wanted.chagok.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 요청 ID: 서버가 만들고, 요청 동안 MDC에 있고, 끝나면 지운다. 로그에 쿼리스트링·토큰을 남기지 않는다. DB 없이 돈다. */
@ExtendWith(OutputCaptureExtension.class)
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    @DisplayName("요청 동안 MDC requestId(UUID), 요청마다 다르고, 끝나면 지워짐. 외부 X-Request-Id 헤더는 쓰지 않음")
    void requestIdLifecycle() throws Exception {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/home");
            request.addHeader("X-Request-Id", "attacker-chosen\nfake log line");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.add(MDC.get(RequestIdFilter.MDC_KEY)));
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        }
        assertThat(seen).hasSize(2).doesNotContainNull().doesNotHaveDuplicates()
            .allMatch(id -> id.matches("[0-9a-f-]{36}"));
    }

    @Test
    @DisplayName("요청 끝 로그: 메서드·경로·상태·시간. 쿼리스트링(연락처 검색어)·Authorization은 남기지 않음")
    void accessLogHasNoQueryOrToken(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
        request.setQueryString("q=010-0000-0001");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.secret-token.sig");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(401));

        assertThat(output.getAll()).contains("GET /api/customers 401")
            .doesNotContain("010-0000-0001").doesNotContain("eyJhbGci").doesNotContain("Bearer");
    }

    @Test
    @DisplayName("다음 필터에서 예외가 나도 MDC는 지워진다")
    void clearsMdcOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/home");
        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception ignored) {
            // 예외는 그대로 전파된다
        }
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
