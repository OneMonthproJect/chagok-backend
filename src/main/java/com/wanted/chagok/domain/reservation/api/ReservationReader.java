package com.wanted.chagok.domain.reservation.api;

import java.util.Collection;
import java.util.Map;

/**
 * reservation이 다른 패키지에 주는 읽기 계약. 구현은 {@code domain.reservation}의 service가 한다.
 * 소비자는 지금 {@code screen/target}(08 14번의 "예약 있음" 표시)이다 (CONVENTIONS 3장).
 *
 * <p>계약은 쓰이는 만큼만 둔다. 예약 상세 패널({@code screen/booking})에 필요한 메서드는 그 이슈에서
 * 이 인터페이스에 <b>더한다</b>(기존 메서드 시그니처는 바꾸지 않는다, CONVENTIONS 2장).
 */
public interface ReservationReader {

    /**
     * 차량별 "유효 예약"을 한 번에 돌려준다. 08 14번의 {@code activeReservation}이고, 같은 기준을 16번의
     * {@code RESERVATION_ACTIVE_EXISTS}도 쓴다 (D-22).
     *
     * <p>유효 예약 = 상태가 {@code RESERVED}이고 예약 날짜가 오늘 이후(오늘 포함). 오늘은 service가 {@code Clock}으로
     * 계산한다(호출하는 쪽이 날짜를 넘기지 않는다). {@code IN_PROGRESS}는 P0-나머지에서 이 기준에 더한다(D-52).
     * 한 차량에 유효 예약이 1건을 넘지 않게 하는 것은 등록 때 service의 규칙이다(D-22, DB 제약은 없고 동시 요청 잠금은
     * P2). 그래서 같은 차량이 둘 이상 나와도 예외 없이 가장 이른 예약 하나를 돌려준다.
     *
     * @param shopId     로그인한 계정의 정비소 (요청 값이 아니다, 07 I-3)
     * @param vehicleIds 확인할 차량. 비어 있으면 빈 맵
     * @return 유효 예약이 있는 차량만 키로 가진 맵. 없는 차량은 키가 없다
     */
    Map<Long, ReservationRef> findActiveByVehicleIds(long shopId, Collection<Long> vehicleIds);
}
