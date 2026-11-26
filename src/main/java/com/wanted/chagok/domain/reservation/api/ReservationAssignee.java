package com.wanted.chagok.domain.reservation.api;

/**
 * 예약 응답에 실리는 담당 정비사 (08 15·17·04·16·19번 {@code assignee}, P0-나머지부터). 이름과 {@code colorIndex}는
 * {@code account.api}의 담당 후보({@code StaffReader.findAssignees}, API 36)와 같은 값이다 — {@code colorIndex} 계산은
 * account 한 곳에만 있고 여기서는 옮겨 담기만 한다(CONVENTIONS 4장). screen이 account를 import하지 않도록 reservation이 이 값을
 * 자기 {@code api}로 내보낸다.
 *
 * @param staffId    담당 정비사 계정 id
 * @param name       이름
 * @param colorIndex 담당 색 (그 정비소 직원 계정 id 순서를 8로 나눈 나머지, 08 7.1)
 */
public record ReservationAssignee(long staffId, String name, int colorIndex) {
}
