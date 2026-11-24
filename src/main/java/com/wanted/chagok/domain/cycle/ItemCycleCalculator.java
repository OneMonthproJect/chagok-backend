package com.wanted.chagok.domain.cycle;

// [뼈대] 담당: 김동윤(dyk816) · 이슈: #19 (PR 2/2, feature/#19-cycle-calc)
// 역할: 항목 상태 계산 구현 (Spring·JPA·다른 도메인 import 금지, 순수 계산, T1~T13)
// 선행: #47 (cycle/api 계약 PR 1/2). 계약은 #47의 domain/cycle/api/ItemCycleCalculator 등을 따른다.
// 이름 주의: api/ItemCycleCalculator는 #47의 계약이고, 이 파일은 그 구현 자리다. 구현 클래스 이름과 테스트 파일은 PR 2에서 정한다.
// 이 파일은 주석만 있는 자리표시자다. 담당자가 구현할 때 이 주석을 지우고 클래스로 바꾼다.
// 어느 이슈의 파일인지(경계)는 chagok-docs/docs/etc/STARTER/08_구현_파일_소유_충돌방지.md 4장을 따른다.
