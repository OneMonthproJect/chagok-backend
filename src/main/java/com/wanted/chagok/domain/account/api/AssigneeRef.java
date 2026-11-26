package com.wanted.chagok.domain.account.api;

import com.wanted.chagok.global.auth.StaffRole;

/**
 * 담당 정비사 후보 한 명 (08 36). 다른 도메인(reservation 등)은 이 값 객체로만 account의 담당 정보를 받는다 (CONVENTIONS 3장).
 *
 * <p>{@code colorIndex}는 08 7.1 정의 그대로다: 그 정비소 직원 계정 id 순서(비활성 포함)를 8로 나눈 나머지,
 * 첫 계정이 0이다. 15·17·04의 {@code assignee.colorIndex}도 같은 값이다.
 */
public record AssigneeRef(long staffId, String name, StaffRole role, int colorIndex) {
}
