package com.wanted.chagok.domain.maintenance.service;

import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.LocalDate;

/**
 * 정비 기록을 누가 수정할 수 있는지 (API 37, 04 F-MNT-04, H3·H10·H11). 사장·담당·기간 판정을 서비스에 {@code if (role == …)}로
 * 흩뜨리지 않고 이 한 곳에서만 한다 (CONVENTIONS 5장, 06 7.5).
 * <ul>
 *   <li>사장은 모든 기록, 기간 제한 없음</li>
 *   <li>정비사는 <b>수정 전 담당이 본인인 기록</b>만. 아니면 403 {@code FORBIDDEN}</li>
 *   <li>정비사는 기준일 + 3일 이내(오늘 ≤ 기준일 + 3일). 넘으면 403 {@code EDIT_PERIOD_EXPIRED}. 담당 여부를 먼저 본다</li>
 * </ul>
 * 기준일은 수정 전 방문일이고, 외부 기록은 저장일이다(방문일이 고객이 말한 과거일이라서). 기준일은 호출하는 쪽이
 * {@code MaintenanceRecord#editPeriodBaseDate()}로 넘긴다.
 */
final class MaintenanceEditRule {

    /** 정비사가 수정할 수 있는 기간: 기준일 + 3일 (04 F-MNT-04, H10). */
    static final int MECHANIC_EDIT_DAYS = 3;

    private MaintenanceEditRule() {
    }

    /** @throws BusinessException 권한이 없거나(403 {@code FORBIDDEN}) 정비사의 기간이 지났을 때(403 {@code EDIT_PERIOD_EXPIRED}) */
    static void requireAllowed(boolean owner, boolean assignee, LocalDate today, LocalDate baseDate) {
        if (owner) {
            return;
        }
        if (!assignee) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (today.isAfter(baseDate.plusDays(MECHANIC_EDIT_DAYS))) {
            throw new BusinessException(ErrorCode.EDIT_PERIOD_EXPIRED);
        }
    }
}
