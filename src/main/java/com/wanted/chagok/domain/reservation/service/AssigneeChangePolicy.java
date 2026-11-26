package com.wanted.chagok.domain.reservation.service;

import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.LocalDate;

/**
 * 담당 재배정이 가능한 때와 사람 (08 19번 「담당 재배정 규칙」, D-53, R24). 사장 전용·당일·마감 판정을 서비스에 {@code if (role == …)}로
 * 흩뿌리지 않고 이 한 곳에서만 한다 (CONVENTIONS 5장, 06 7.5). 진행중 예약은 이 판정 앞의 상태 확인에서
 * 409 {@code INVALID_STATUS_TRANSITION}이라 여기에 오지 않는다.
 *
 * <p>기준 날짜는 <b>변경 전 예약 날짜</b>다(일시와 담당을 한 요청에 같이 보내도 변경 전 날짜로 판정).
 * <ul>
 *   <li>오늘 &lt; 예약 날짜(전날까지): 사장·정비사 모두 가능, 사유는 선택</li>
 *   <li>오늘 = 예약 날짜: 사장만 가능하고 사유 필수(없으면 400 {@code INVALID_INPUT}), 정비사는 409 {@code ASSIGNEE_CHANGE_CLOSED}</li>
 *   <li>예약 날짜 &lt; 오늘: 사장도 409 {@code ASSIGNEE_CHANGE_CLOSED}</li>
 * </ul>
 */
final class AssigneeChangePolicy {

    private AssigneeChangePolicy() {
    }

    /**
     * @param owner           요청한 계정이 사장인가
     * @param today           오늘 ({@code Clock}에서 꺼낸 값)
     * @param reservationDate 변경 전 예약 날짜
     * @param reason          재배정 사유 (없거나 공백이면 없는 것)
     * @throws BusinessException 마감이 지났거나(409 {@code ASSIGNEE_CHANGE_CLOSED}) 당일 사장 재배정에 사유가 없을 때(400)
     */
    static void requireAllowed(boolean owner, LocalDate today, LocalDate reservationDate, String reason) {
        if (today.isBefore(reservationDate)) {
            return;
        }
        if (today.isEqual(reservationDate) && owner) {
            if (reason == null || reason.isBlank()) {
                throw BusinessException.invalidField("reassignReason", "당일 재배정은 사유를 입력해 주세요.");
            }
            return;
        }
        throw new BusinessException(ErrorCode.ASSIGNEE_CHANGE_CLOSED);
    }
}
