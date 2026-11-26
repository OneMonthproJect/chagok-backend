package com.wanted.chagok.domain.account.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * account가 다른 도메인에 주는 읽기 계약 (maintenance 방문 이력 API 11의 담당 정비사 이름, 예약 담당 후보 API 36 등).
 * 구현은 {@code StaffService}. 기존 메서드는 바꾸지 않고 메서드를 더한다 (CONVENTIONS 3장).
 */
public interface StaffReader {

    /**
     * 정비사·사장 여러 명의 이름을 한 번에 조회한다 (건별 호출 금지, N+1 방지).
     * 없거나 다른 정비소 staff는 결과에 없다(오류 아님, 07 I-3). 활성 여부로 거르지 않는다 —
     * 과거 기록의 담당자 이름은 비활성 뒤에도 보여야 한다. 입력이 비어 있으면 빈 Map.
     *
     * @return staffId → 이름
     */
    Map<Long, String> findNames(long shopId, Collection<Long> staffIds);

    /**
     * 그 정비소의 담당 정비사 후보(활성 사장·정비사)를 staffId 오름차순으로 돌려준다 (08 36, F-RSV-01·06).
     * 다른 정비소 직원은 shopId 조건으로 빠진다 (07 I-3). 정비소 직원 수 상한이 작아 쪽 나누기는 없다.
     * 직원이 없으면 빈 목록(오류 아님).
     *
     * <p>{@code colorIndex}는 {@link AssigneeRef} 정의대로 이 한 곳에서만 계산한다. 15·17·04의 {@code assignee}는
     * 이 값을 그대로 쓴다(#89).
     */
    List<AssigneeRef> findAssignees(long shopId);
}
