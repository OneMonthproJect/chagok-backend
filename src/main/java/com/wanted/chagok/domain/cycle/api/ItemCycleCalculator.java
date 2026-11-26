package com.wanted.chagok.domain.cycle.api;

import com.wanted.chagok.domain.cycle.service.DefaultItemCycleCalculator;
import java.time.LocalDate;
import java.util.List;

/**
 * 항목 하나의 점검 주기 상태를 계산한다 (04 F-CYC-01). 항목 상태의 단일 원본이며, 다른 곳에서 날짜·km 비교로
 * 상태를 다시 계산하지 않는다 (CONVENTIONS 4장). 여러 항목은 호출하는 쪽이 항목마다 한 번씩 부른다.
 *
 * <p>구현은 {@code domain.cycle} 안쪽의 plain Java 클래스이며 계산은 값만 받아 값을 돌려준다(DB·Spring 없음).
 * 현재 시각을 직접 읽지 않으므로 {@code today}는 호출하는 쪽이 {@code Clock}에서 꺼내 넘긴다.
 *
 * <p>cycle은 Spring Bean을 만들 수 없으므로(ArchitectureTest {@code cycle은_순수_계산이다}) 호출하는 쪽은
 * {@link #standard()}로 인스턴스를 얻는다. 구현 클래스를 직접 import하지 않는다 (CONVENTIONS 1장 1번).
 * 호출하는 쪽이 Bean이 필요하면 자기 설정에서 {@code standard()}를 {@code @Bean}으로 감싼다.
 */
public interface ItemCycleCalculator {

    /**
     * 표준 계산기를 돌려준다. 상태가 없는 순수 계산이라 한 번 만든 인스턴스를 여러 곳에서 공유해도 된다.
     * 구현을 얻는 통로일 뿐 계산 규칙은 담지 않는다.
     */
    static ItemCycleCalculator standard() {
        return new DefaultItemCycleCalculator();
    }

    /**
     * @param rule         그 항목의 주기 (개월·km)
     * @param records      그 차량·그 항목의 정비 기록 전부. 점검·거절도 포함하며, 기준 기록은 cycle이
     *                     {@link CycleRecord#resetsCycle()}로 고른다. 비어 있으면 미확인
     * @param today        판정 기준일(오늘)
     * @param visitMileage 방문 때 입력한 주행거리. 입력하지 않았으면 null이고 날짜 기준만 판정한다
     * @return 상태와 근거
     */
    ItemCycleResult calculate(CycleRule rule, List<CycleRecord> records, LocalDate today, Integer visitMileage);

    /**
     * 지난 방문 거절·지난 점검 주의 표시를 판정한다 (04 F-CYC-02, T14·T15). 상태 계산({@link #calculate})과 별개이며
     * 상태를 바꾸지 않는다. 표시 판정의 단일 원본이라 다른 곳에서 날짜 비교로 표시를 다시 계산하지 않는다 (CONVENTIONS 4장).
     *
     * <p>"가장 최근 기록"은 {@code calculate}의 기준 기록 선택과 같은 순서(날짜, 저장 시각, id)로 고른다.
     * <ul>
     *   <li>가장 최근 기록이 거절이고 기준 기록(교체·외부 중 가장 최근)보다 늦으면 {@code lastDecline}.
     *       기준 기록이 아직 없어도(거절만 있어도) 표시한다.</li>
     *   <li>가장 최근 기록이 점검이고 결과가 주의이면 {@code lastCaution}.</li>
     *   <li>같은 항목이 다시 기록되면(구분 무관) 앞선 표시는 사라진다. 가장 최근 기록 하나만 보기 때문이다.</li>
     * </ul>
     *
     * @param records 그 차량·그 항목의 정비 기록 전부. 비어 있으면 표시 없음
     * @return 표시 판정 결과. 표시가 없으면 두 값이 모두 null
     */
    CycleNotes judgeNotes(List<CycleNoteRecord> records);
}
