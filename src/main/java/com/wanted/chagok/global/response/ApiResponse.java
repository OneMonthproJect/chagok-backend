package com.wanted.chagok.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wanted.chagok.global.error.ErrorCode;
import java.util.List;

/**
 * 모든 API 응답의 모양 (08 0.2). 성공은 {@code data}, 실패는 {@code error} 하나만 채운다.
 *
 * <p>값이 없는 필드도 {@code null}로 내보낸다(필드를 빼지 않는다). 화면은 언제나 같은 세 필드를 읽는다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> fail(ErrorCode code, List<FieldErrorDetail> fieldErrors, Object details) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), code.message(),
            fieldErrors == null ? List.of() : List.copyOf(fieldErrors), details));
    }

    public static ApiResponse<Void> fail(ErrorCode code) {
        return fail(code, List.of(), null);
    }

    /**
     * 08 0.2 {@code error}. {@code fieldErrors}는 {@code INVALID_INPUT}일 때만 채우고 그 밖에는 빈 배열,
     * {@code details}는 화면이 다음 행동을 안내하는 데 필요한 값만(예: {@code { "reservationId": 3 }}), 그 밖에는 null.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ErrorBody(String code, String message, List<FieldErrorDetail> fieldErrors, Object details) {
    }

    /** 08 0.2 {@code fieldErrors[]}: 어느 입력이 왜 틀렸는지. */
    public record FieldErrorDetail(String field, String reason) {
    }
}
