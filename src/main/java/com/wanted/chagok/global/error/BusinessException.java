package com.wanted.chagok.global.error;

import com.wanted.chagok.global.response.ApiResponse.FieldErrorDetail;
import java.util.List;

/**
 * 업무 규칙 위반. Controller는 try/catch하지 않고 이 예외를 던진다(CONVENTIONS 1장 5번). 응답은 전역 처리기 한 곳에서 만든다.
 *
 * <p>표준 예외를 감쌀 때는 원인을 넘긴다: {@code new BusinessException(code, e)}. 원인은 서버 로그에만 남고 응답에는 나가지 않는다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldErrorDetail> fieldErrors;
    private final Object details;

    public BusinessException(ErrorCode code) {
        this(code, List.of(), null, null);
    }

    public BusinessException(ErrorCode code, Throwable cause) {
        this(code, List.of(), null, cause);
    }

    /** {@code details}는 08 0.2대로 화면의 다음 행동에 필요한 값만 (예: {@code Map.of("reservationId", 3)}). */
    public BusinessException(ErrorCode code, Object details) {
        this(code, List.of(), details, null);
    }

    public BusinessException(ErrorCode code, List<FieldErrorDetail> fieldErrors, Object details, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        this.details = details;
    }

    /** 입력 하나가 틀렸을 때: 400 {@code INVALID_INPUT} + {@code fieldErrors} 한 줄. */
    public static BusinessException invalidField(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_INPUT, List.of(new FieldErrorDetail(field, reason)), null, null);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldErrorDetail> fieldErrors() {
        return fieldErrors;
    }

    public Object details() {
        return details;
    }
}
