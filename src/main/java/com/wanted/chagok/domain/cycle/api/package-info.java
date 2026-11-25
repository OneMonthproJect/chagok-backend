/**
 * cycle이 바깥에 내보내는 값(enum·record). 다른 패키지는 이 안의 타입만 쓴다 (CONVENTIONS 2장).
 * 계산 본체는 {@code domain.cycle.service}에 있고, 이 패키지에는 구현을 두지 않는다. 단 하나의 예외로
 * {@link com.wanted.chagok.domain.cycle.api.ItemCycleCalculator#standard()}가 구현 인스턴스를 만들어 돌려주는
 * 한 줄 위임만 둔다(cycle은 Spring Bean을 만들 수 없어서). 이 통로를 더 늘리지 않는다.
 */
package com.wanted.chagok.domain.cycle.api;
