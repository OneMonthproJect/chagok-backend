package com.wanted.chagok.global.auth;

import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보안 필터 단계에서 끝나는 요청의 응답을 08 0.2 모양으로 만든다 (Controller까지 가지 않으므로 GlobalExceptionHandler가 못 받는다).
 *
 * <ul>
 *   <li>인증 실패(토큰 없음·만료·위조·형식 오류·클레임 규약 위반) → 401 {@code AUTH_REQUIRED}</li>
 *   <li>권한 부족(서비스 관리자 → 정비소 API, 정비소 계정 → 관리자 API, 등록 안 된 경로) → 403 {@code FORBIDDEN}</li>
 * </ul>
 * 어느 검증에서 실패했는지는 응답에 넣지 않는다(서버 로그에만, 토큰 값은 남기지 않음).
 */
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorResponder.class);

    private final JsonMapper jsonMapper;

    public SecurityErrorResponder(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
        throws IOException {
        log.debug("authentication failed: {}", e.getClass().getSimpleName());
        write(response, ErrorCode.AUTH_REQUIRED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
        throws IOException {
        log.debug("access denied: {} {}", request.getMethod(), request.getRequestURI());
        write(response, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), ApiResponse.fail(code));
    }
}
