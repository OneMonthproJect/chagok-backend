package com.wanted.chagok.domain.account.api;

import java.util.Collection;
import java.util.Map;

/**
 * account가 다른 도메인에 주는 읽기 계약 (maintenance 방문 이력 API 11의 담당 정비사 이름 등).
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
}
