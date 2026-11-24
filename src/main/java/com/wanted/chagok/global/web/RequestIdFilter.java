package com.wanted.chagok.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 ID를 붙여 그 요청의 모든 로그 줄에 남긴다 (CONVENTIONS 6장: 요청 ID·URL·상태·처리 시간).
 *
 * <ul>
 *   <li>ID는 서버가 만든다(UUID). 요청 헤더로 온 값은 믿지 않는다 — 로그 위조·주입을 막는다</li>
 *   <li>보안 필터보다 먼저 돈다(가장 높은 순서). 그래서 401·403으로 끝난 요청도 같은 ID로 남는다</li>
 *   <li>요청 끝 한 줄: 메서드·경로·상태·처리 시간. <b>쿼리스트링은 남기지 않는다</b>(검색어에 연락처·이름이 들어감, 05 POL-38),
 *       {@code Authorization} 헤더도 남기지 않는다</li>
 *   <li>응답 헤더로 내보내지 않는다(08에 없음)</li>
 * </ul>
 *
 * <p>로그 패턴은 {@code application.yaml}의 {@code logging.pattern.level}이 {@code %X{requestId}}를 붙인다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long start = System.nanoTime();
        MDC.put(MDC_KEY, UUID.randomUUID().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs);
            MDC.remove(MDC_KEY);
        }
    }
}
