package com.wanted.chagok.global.error;

import org.springframework.http.HttpStatus;

/**
 * 08 0.3 에러 코드와 1:1. 지금은 P0-핵심 11개만 둔다.
 *
 * <p>P0-나머지·P1 코드({@code MECHANIC_SLOT_CONFLICT}, {@code EDIT_PERIOD_EXPIRED}, {@code SHOP_SUSPENDED} 등)는
 * 그 단계 이슈에서 08과 같은 PR로 더한다(CONVENTIONS 6장). {@code message}는 화면에 그대로 보여 줄 수 있는 한국어 문장이다.
 */
public enum ErrorCode {

    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "이 기능을 사용할 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    RESERVATION_PAST_DATE(HttpStatus.BAD_REQUEST, "지난 날짜로는 예약할 수 없습니다."),
    RESERVATION_ACTIVE_EXISTS(HttpStatus.CONFLICT, "이 차량에는 이미 유효한 예약이 있습니다."),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "지금 상태에서는 할 수 없는 변경입니다."),
    CONFLICT(HttpStatus.CONFLICT, "이미 사용 중인 값입니다."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "다른 직원이 먼저 수정했습니다. 최신 내용을 다시 불러와 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "처리 중 오류가 발생했습니다. 저장되었는지 확인한 뒤 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
