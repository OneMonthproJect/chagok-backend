package com.wanted.chagok.global.error;

import com.wanted.chagok.global.response.ApiResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨테이너가 오류를 {@code /error}로 넘기는 경우(보안 방화벽의 400, Spring MVC 앞에서 나는 오류 등)에도 08 0.2 공통 응답으로 내보낸다 (#17).
 * 상태·코드는 {@link GlobalExceptionHandler#forHttpStatus}의 규칙을 그대로 쓰고, 예외 메시지·경로는 응답에 넣지 않는다.
 * ERROR 디스패치만 보안 필터를 통과하며(SecurityConfig), 이 경로를 직접 부르는 요청은 막힌다.
 */
@RestController
public class ErrorDispatchController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value ? value : 500;
        ErrorCode code = GlobalExceptionHandler.forHttpStatus(status);
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON)
            .body(ApiResponse.fail(code, List.of(), null));
    }
}
