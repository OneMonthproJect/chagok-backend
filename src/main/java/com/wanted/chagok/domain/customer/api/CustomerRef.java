package com.wanted.chagok.domain.customer.api;

import java.util.List;

/**
 * 우리 정비소 고객 한 명과 그 차량 목록 (Entity가 아니라 값). 다른 도메인은 이 값으로만 고객을 안다 (CONVENTIONS 3장).
 *
 * <p>{@code phone}은 P0-핵심에서 전체 번호다 (08 0.5, D-40). P0-나머지의 마스킹(F-CUS-07)은 이 계약의 값에 customer가
 * 한 곳에서 적용한다. {@code vehicles}는 id 오름차순이고 차량이 없으면 빈 목록이다.
 */
public record CustomerRef(
    long customerId,
    String name,
    String phone,
    boolean consent,
    List<VehicleRef> vehicles
) {
}
