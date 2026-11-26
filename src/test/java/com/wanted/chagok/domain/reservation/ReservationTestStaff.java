package com.wanted.chagok.domain.reservation;

import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;

/**
 * 예약 변경(API 19) 테스트가 쓰는 로그인 계정. 재배정이 아닌 호출은 계정이 결과에 영향을 주지 않아 사장으로 둔다.
 * 재배정 테스트는 이력의 수정자 FK가 걸리므로 실제 계정 id를 직접 넘긴다.
 */
final class ReservationTestStaff {

    private ReservationTestStaff() {
    }

    static LoginStaff owner(long shopId) {
        return new LoginStaff(0L, shopId, StaffRole.OWNER);
    }
}
