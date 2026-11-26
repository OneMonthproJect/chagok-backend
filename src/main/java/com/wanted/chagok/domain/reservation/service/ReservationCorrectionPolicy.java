package com.wanted.chagok.domain.reservation.service;

import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.LocalDate;

/**
 * 지난 예약 정정(API 40)을 누가 할 수 있는지 (08 7.1 40번, D-53, R27~R29). 사장 전용·담당·기간 판정을 서비스에
 * {@code if (role == …)}로 흩뿌리지 않고 이 한 곳에서만 한다 (CONVENTIONS 5장, 06 7.5).
 * <ul>
 *   <li>상태 정정은 사장만. 정비사가 {@code status}를 보내면(같은 값이어도) 403 {@code FORBIDDEN}</li>
 *   <li>메모 정정은 사장은 기간 제한 없이, 정비사는 그 예약의 <b>담당</b>일 때만 예약 날짜 + 3일 이내(오늘 ≤ 예약 날짜 + 3일).
 *       담당이 아니면 403 {@code FORBIDDEN}, 기간이 지났으면 403 {@code EDIT_PERIOD_EXPIRED}이고 담당 여부를 먼저 본다</li>
 * </ul>
 * 허용표(어느 상태에서 어느 상태로)와 "끝난 예약인지"는 Entity가 판정한다.
 */
final class ReservationCorrectionPolicy {

    /** 담당 정비사가 메모를 고칠 수 있는 기간: 예약 날짜 + 3일 (08 40번, 04 R28). */
    static final int MECHANIC_MEMO_EDIT_DAYS = 3;

    private ReservationCorrectionPolicy() {
    }

    /**
     * @param owner           요청한 계정이 사장인가
     * @param assignee        요청한 계정이 그 예약의 담당 정비사인가
     * @param correctsStatus  요청에 {@code status}가 있는가 (지금 값과 같아도 보냈으면 true)
     * @param correctsMemo    요청에 {@code purposeMemo}가 있는가 (빈 문자열도 보낸 것)
     * @param today           오늘 ({@code Clock}에서 꺼낸 값)
     * @param reservationDate 예약 날짜
     * @throws BusinessException 권한이 없거나(403 {@code FORBIDDEN}) 담당 정비사의 기간이 지났을 때(403 {@code EDIT_PERIOD_EXPIRED})
     */
    static void requireAllowed(boolean owner, boolean assignee, boolean correctsStatus, boolean correctsMemo,
                               LocalDate today, LocalDate reservationDate) {
        if (owner) {
            return;
        }
        if (correctsStatus) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (correctsMemo) {
            if (!assignee) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            if (today.isAfter(reservationDate.plusDays(MECHANIC_MEMO_EDIT_DAYS))) {
                throw new BusinessException(ErrorCode.EDIT_PERIOD_EXPIRED);
            }
        }
    }
}
