package com.wanted.chagok.domain.reservation.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약 한 건의 상세 값 (08 17번 {@code reservation}의 재료). Entity가 아니라 값이다 (CONVENTIONS 2장).
 * 예약 목적 항목은 id만 준다 — 이름은 호출하는 쪽이 {@code item.api}로 가져온다(다른 도메인은 api로만, D-38).
 *
 * @param reservationId  예약 번호
 * @param vehicleId      그 예약의 차량
 * @param reservedAt     예약 일시 (Asia/Seoul, 시간대 표기 없음)
 * @param status         예약 상태 이름 ({@code RESERVED}·{@code COMPLETED} 등, Entity enum을 내보내지 않으려고 문자열)
 * @param needsAction    "처리 필요" 표시 (D-23): 상태가 {@code RESERVED}인데 예약 시각이 현재보다 이전. 판정은 reservation 한 곳
 * @param version        동시 수정 확인용 번호 (D-42). 화면이 18~21·39·40 요청에 그대로 보낸다
 * @param purposeItemIds 예약 목적으로 고른 항목 id. 없으면 빈 목록
 * @param purposeOther   예약 목적 "기타" 선택 여부
 * @param purposeMemo    예약 목적 메모. 없으면 null
 */
public record ReservationDetail(
    long reservationId,
    long vehicleId,
    LocalDateTime reservedAt,
    String status,
    boolean needsAction,
    int version,
    List<Long> purposeItemIds,
    boolean purposeOther,
    String purposeMemo
) {
}
