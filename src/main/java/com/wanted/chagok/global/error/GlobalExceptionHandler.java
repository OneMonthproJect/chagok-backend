package com.wanted.chagok.global.error;

import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.global.response.ApiResponse.FieldErrorDetail;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 오류 응답을 08 0.2 모양 하나로 만든다 (08 0.3, 05 POL-120~129).
 *
 * <ul>
 *   <li>업무 예외 {@link BusinessException} → 그 {@link ErrorCode}</li>
 *   <li>검증 실패·잘못된 JSON·타입 불일치·필수 파라미터 누락·405·406·415 → 400 {@code INVALID_INPUT} (08 0.3: 표준 예외도 감싼다)</li>
 *   <li>없는 경로 → 404 {@code NOT_FOUND}</li>
 *   <li>{@code AccessDeniedException} → 403 {@code FORBIDDEN}, {@code ResponseStatusException} → 그 상태(401·403·404는 해당 코드, 그 밖 4xx는 {@code INVALID_INPUT}, 5xx는 {@code INTERNAL_ERROR})</li>
 *   <li>JPA {@code @Version} 충돌 → 409 {@code CONCURRENT_MODIFICATION} (08 0.3 M1 결정 A)</li>
 *   <li>그 밖 → 500 {@code INTERNAL_ERROR}. DB 제약 위반({@code DataIntegrityViolationException})도 여기다 —
 *       복합 FK·CHECK 위반은 서버 검증이 먼저 막아야 하는 버그이고, 사용자가 고칠 수 있는 유일 제약 위반만 업무 코드가
 *       {@code CONFLICT}로 던진다(08 0.3 M1 결정 A)</li>
 * </ul>
 *
 * <p>응답에는 예외 메시지·스택·SQL을 넣지 않는다. 원인은 서버 로그에만 남긴다(요청 ID와 함께, {@code RequestIdFilter}).
 * 401·403 중 보안 필터에서 나는 것은 {@code global.auth.SecurityErrorResponder}가 같은 모양으로 만든다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        if (e.code().status().is5xxServerError()) {
            log.error("business error {}", e.code(), e);
        } else {
            log.debug("business error {}", e.code(), e.getCause());
        }
        return respond(e.code(), e.fieldErrors(), e.details());
    }

    /** {@code @Valid @RequestBody}·{@code @ModelAttribute} 검증 실패 (MethodArgumentNotValidException은 BindException의 하위). */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException e) {
        List<FieldErrorDetail> fields = e.getBindingResult().getFieldErrors().stream()
            .map(f -> new FieldErrorDetail(f.getField(), f.getDefaultMessage()))
            .toList();
        return respond(ErrorCode.INVALID_INPUT, fields, null);
    }

    /** {@code @RequestParam}·{@code @PathVariable}의 제약(@Min 등) 위반. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException e) {
        List<FieldErrorDetail> fields = e.getParameterValidationResults().stream()
            .flatMap(r -> r.getResolvableErrors().stream()
                .map(err -> new FieldErrorDetail(r.getMethodParameter().getParameterName(), err.getDefaultMessage())))
            .toList();
        return respond(ErrorCode.INVALID_INPUT, fields, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(ErrorCode.INVALID_INPUT, List.of(new FieldErrorDetail(e.getName(), "형식이 올바르지 않습니다.")), null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return respond(ErrorCode.INVALID_INPUT, List.of(new FieldErrorDetail(e.getParameterName(), "필수 입력입니다.")), null);
    }

    /** 잘못된 JSON, 405, 406, 415: 어느 필드인지 특정할 수 없으므로 fieldErrors는 비운다. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        log.debug("bad request: {}", e.getClass().getSimpleName());
        return respond(ErrorCode.INVALID_INPUT, List.of(), null);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoResource(Exception e) {
        return respond(ErrorCode.NOT_FOUND, List.of(), null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.info("concurrent modification: {}", e.getClass().getSimpleName());
        return respond(ErrorCode.CONCURRENT_MODIFICATION, List.of(), null);
    }

    /**
     * 메서드·서비스에서 던진 {@code AccessDeniedException}은 403이다. 보안 필터 단계의 403은 {@code SecurityErrorResponder}가 만든다.
     * 예외 메시지는 응답에 넣지 않는다.
     *
     * <p>주의: 메서드 보안({@code @PreAuthorize})을 도입하면 익명 사용자의 거부도 여기서 403이 된다. 08 0.2는 인증 없음을 401로 구분하므로,
     * 그때는 익명이면 다시 던져 보안 필터의 entry point(401)가 처리하게 한다. 지금은 던지는 코드가 없다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        log.debug("access denied: {}", e.getClass().getSimpleName());
        return respond(ErrorCode.FORBIDDEN, List.of(), null);
    }

    /** 코드가 던진 상태를 지킨다: 401·403·404는 그 코드, 그 밖 4xx는 {@code INVALID_INPUT}, 5xx는 {@code INTERNAL_ERROR}. reason은 응답에 넣지 않는다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException e) {
        int status = e.getStatusCode().value();
        ErrorCode code = forHttpStatus(status);
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("response status {}", status, e);
        } else {
            log.debug("response status {}", status);
        }
        return respond(code, List.of(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return respond(ErrorCode.INTERNAL_ERROR, List.of(), null);
    }

    /** HTTP 상태 → 공통 코드. 401·403·404는 그 코드, 그 밖 4xx는 {@code INVALID_INPUT}, 5xx는 {@code INTERNAL_ERROR}. {@code /error}도 같은 규칙을 쓴다. */
    static ErrorCode forHttpStatus(int status) {
        if (status >= 500) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return switch (status) {
            case 401 -> ErrorCode.AUTH_REQUIRED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            default -> ErrorCode.INVALID_INPUT;
        };
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, List<FieldErrorDetail> fields, Object details) {
        // Content-Type을 JSON으로 고정한다: Accept가 JSON이 아니면(406 상황) 본문을 쓰지 못해 /error로 넘어가고, 보안 필터가 403으로 바꾼다 (#17)
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.fail(code, fields, details));
    }
}
