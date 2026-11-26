package com.wanted.chagok.domain.customer.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * API 22 요청 (08 22). {@code version}은 필수: 빠지거나 숫자가 아니면 400 {@code INVALID_INPUT}, 숫자인데 서버 값과 다르면
 * 409 {@code CONCURRENT_MODIFICATION}. 나머지는 보낸 것만 바꾸고(없거나 {@code null}이면 그대로), 보냈다면 06과 같은 검증을 받는다.
 * 요청에 {@code shopId}가 있어도 읽지 않는다(X8).
 *
 * @param version 고객 상세(07) 응답의 {@code version}
 */
public record CustomerUpdateRequest(
    @NotNull(message = "필수 입력입니다.")
    Integer version,

    @Pattern(regexp = ".+", message = "비울 수 없습니다.")
    @Size(max = 50, message = "50자 이하로 입력해 주세요.")
    String name,

    @Pattern(regexp = "^[0-9-]*$", message = "숫자와 하이픈만 입력할 수 있습니다.")
    @Size(min = 9, max = 20, message = "9~20자로 입력해 주세요.")
    String phone,

    Boolean consent
) {

    public CustomerUpdateRequest {
        name = name == null ? null : name.strip();
        phone = phone == null ? null : phone.strip();
    }
}
