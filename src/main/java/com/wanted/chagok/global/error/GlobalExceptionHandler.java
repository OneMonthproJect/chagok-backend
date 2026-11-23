package com.wanted.chagok.global.error;

import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.global.response.ApiResponse.FieldErrorDetail;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 오류 응답을 08 0.2 모양 하나로 만든다 (08 0.3, 05 POL-120~129).
 *
 * <ul>
 *   <li>업무 예외 {@link BusinessException} → 그 {@link ErrorCode}</li>
 *   <li>검증 실패·잘못된 JSON·타입 불일치·필수 파라미터 누락·405·415 → 400 {@code INVALID_INPUT} (08 0.3: 표준 예외도 감싼다)</li>
 *   <li>없는 경로 → 404 {@code NOT_FOUND}</li>
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

    /** 잘못된 JSON, 405, 415: 어느 필드인지 특정할 수 없으므로 fieldErrors는 비운다. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeNotSupportedException.class})
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return respond(ErrorCode.INTERNAL_ERROR, List.of(), null);
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, List<FieldErrorDetail> fields, Object details) {
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, fields, details));
    }
}
