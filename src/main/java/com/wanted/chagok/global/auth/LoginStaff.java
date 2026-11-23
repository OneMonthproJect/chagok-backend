package com.wanted.chagok.global.auth;

/**
 * 로그인한 사장·정비사. 정비소 데이터는 요청 값이 아니라 이 {@code shopId}로만 조회한다 (08 0.1, 07 I-3).
 * Controller에서는 {@link CurrentStaff}로 받는다.
 */
public record LoginStaff(long staffId, long shopId, StaffRole role) {

    public boolean isOwner() {
        return role == StaffRole.OWNER;
    }
}
