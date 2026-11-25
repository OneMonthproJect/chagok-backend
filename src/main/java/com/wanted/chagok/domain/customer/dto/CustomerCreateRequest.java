package com.wanted.chagok.domain.customer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * API 06 요청 (08 06). 검증 실패는 400 {@code INVALID_INPUT} + {@code fieldErrors}.
 * 연식의 상한(현재 연도 + 1)은 시각이 필요해 서비스가 {@code Clock}으로 확인한다.
 * 요청에 {@code shopId}가 있어도 읽지 않는다(X8, 정비소는 토큰에서만).
 *
 * <p>값 정리: 이름·연락처는 앞뒤 공백, 차량번호는 모든 공백을 제거한 뒤 검증한다 (04 F-CUS-02: 차량번호는 형식 검사 없이 공백만 제거).
 */
public record CustomerCreateRequest(
    @NotBlank(message = "필수 입력입니다.")
    @Size(max = 50, message = "50자 이하로 입력해 주세요.")
    String name,

    @NotBlank(message = "필수 입력입니다.")
    @Pattern(regexp = "^[0-9-]*$", message = "숫자와 하이픈만 입력할 수 있습니다.")
    @Size(min = 9, max = 20, message = "9~20자로 입력해 주세요.")
    String phone,

    @NotNull(message = "필수 입력입니다.")
    Boolean consent,

    @NotNull(message = "필수 입력입니다.")
    @Valid
    VehicleInput vehicle
) {

    public CustomerCreateRequest {
        name = name == null ? null : name.strip();
        phone = phone == null ? null : phone.strip();
    }

    public record VehicleInput(
        @NotBlank(message = "필수 입력입니다.")
        @Size(max = 20, message = "20자 이하로 입력해 주세요.")
        String plateNumber,

        @NotBlank(message = "필수 입력입니다.")
        @Size(max = 50, message = "50자 이하로 입력해 주세요.")
        String model,

        @Min(value = 1980, message = "1980년 이후여야 합니다.")
        Integer modelYear,

        @Min(value = 0, message = "0 이상이어야 합니다.")
        @Max(value = 2_000_000, message = "2,000,000 이하여야 합니다.")
        Integer mileage
    ) {

        public VehicleInput {
            plateNumber = plateNumber == null ? null : plateNumber.replaceAll("\\s+", "");
            model = model == null ? null : model.strip();
        }
    }
}
