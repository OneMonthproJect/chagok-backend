package com.wanted.chagok.domain.cycle.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 항목 하나의 점검 주기 상태를 계산한다 (04 F-CYC-01). 항목 상태의 단일 원본이며, 다른 곳에서 날짜·km 비교로
 * 상태를 다시 계산하지 않는다 (CONVENTIONS 4장). 여러 항목은 호출하는 쪽이 항목마다 한 번씩 부른다.
 *
 * <p>구현은 {@code domain.cycle} 안쪽의 plain Java 클래스이며 계산은 값만 받아 값을 돌려준다(DB·Spring 없음).
 * 현재 시각을 직접 읽지 않으므로 {@code today}는 호출하는 쪽이 {@code Clock}에서 꺼내 넘긴다.
 */
public interface ItemCycleCalculator {

    /**
     * @param rule         그 항목의 주기 (개월·km)
     * @param records      그 차량·그 항목의 정비 기록 전부. 점검·거절도 포함하며, 기준 기록은 cycle이
     *                     {@link CycleRecord#resetsCycle()}로 고른다. 비어 있으면 미확인
     * @param today        판정 기준일(오늘)
     * @param visitMileage 방문 때 입력한 주행거리. 입력하지 않았으면 null이고 날짜 기준만 판정한다
     * @return 상태와 근거
     */
    ItemCycleResult calculate(CycleRule rule, List<CycleRecord> records, LocalDate today, Integer visitMileage);
}
