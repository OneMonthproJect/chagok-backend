package com.wanted.chagok.domain.customer.api;

/**
 * customer가 다른 도메인에 주는 쓰기 계약 (maintenance 방문·정비 기록 저장 API 12). 읽기 계약 {@link CustomerReader}와 나눠 둔다.
 * 구현은 {@code CustomerService}. 기존 메서드는 바꾸지 않고 메서드를 더한다 (CONVENTIONS 3장).
 */
public interface CustomerWriter {

    /**
     * 방문 저장 트랜잭션의 첫 차량 조회에서 행을 잠근다. 잠금은 트랜잭션 종료까지 유지된다.
     * 없거나 다른 정비소 차량이면 404 {@code NOT_FOUND}.
     */
    VehicleRef getOwnedVehicleForVisit(long shopId, long vehicleId);

    /**
     * 방문·정비 기록을 저장할 때 차량에 연식과 최근 주행거리를 반영한다 (08 12, 04 F-MNT-01, D-58, 05 POL-60).
     * 호출하는 쪽의 트랜잭션에 참여하므로, 기록 저장이 실패하면 이 변경도 함께 취소된다.
     *
     * <ul>
     *   <li>없거나 다른 정비소 차량이면 404 {@code NOT_FOUND}.</li>
     *   <li>{@code modelYear}: 차량의 연식이 비어 있을 때만 채운다. 이미 있으면 바꾸지 않는다. 값이 있으면 범위(1980 ~ 현재 연도 + 1)를
     *       확인하고 벗어나면 400 {@code INVALID_INPUT}({@code fieldErrors}의 필드는 {@code vehicle.modelYear}).</li>
     *   <li>{@code mileage}: 차량의 최근 주행거리가 비어 있거나 이 값이 더 클 때만 갱신한다. 같거나 작으면 바꾸지 않고 오류도 아니다.
     *       0 미만이거나 2,000,000 초과이면 400 {@code INVALID_INPUT}({@code fieldErrors}의 필드는 {@code mileage}).</li>
     *   <li>둘 다 {@code null}이면 아무것도 바꾸지 않는다(차량 소속은 확인한다).</li>
     * </ul>
     */
    void applyVisit(long shopId, long vehicleId, Integer modelYear, Integer mileage);
}
