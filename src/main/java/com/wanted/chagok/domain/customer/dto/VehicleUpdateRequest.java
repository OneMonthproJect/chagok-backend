package com.wanted.chagok.domain.customer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * API 23 요청 (08 23). {@code version}은 22와 같다. 나머지는 보낸 것만 바꾸고, 보냈다면 08 차량 추가와 같은 검증을 받는다.
 * 고객은 바꿀 수 없고 요청에 있어도 읽지 않는다. 연식의 상한(현재 연도 + 1)은 서비스가 {@code Clock}으로 확인한다.
 *
 * @param version 차량 정보(09) 응답의 {@code version}
 */
public record VehicleUpdateRequest(
    @NotNull(message = "필수 입력입니다.")
    Integer version,

    @Pattern(regexp = ".+", message = "비울 수 없습니다.")
    @Size(max = 20, message = "20자 이하로 입력해 주세요.")
    String plateNumber,

    @Pattern(regexp = ".+", message = "비울 수 없습니다.")
    @Size(max = 50, message = "50자 이하로 입력해 주세요.")
    String model,

    @Min(value = 1980, message = "1980년 이후여야 합니다.")
    Integer modelYear,

    @Min(value = 0, message = "0 이상이어야 합니다.")
    @Max(value = 2_000_000, message = "2,000,000 이하여야 합니다.")
    Integer latestMileage
) {

    public VehicleUpdateRequest {
        plateNumber = plateNumber == null ? null : plateNumber.replaceAll("\\s+", "");
        model = model == null ? null : model.strip();
    }
}
