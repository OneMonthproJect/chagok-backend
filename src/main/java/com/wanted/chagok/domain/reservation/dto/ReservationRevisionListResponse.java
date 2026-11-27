package com.wanted.chagok.domain.reservation.dto;

import com.wanted.chagok.domain.reservation.entity.RevisionKind;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API 41 예약 이력 조회 응답 (08 7.1 41번). {@code changedAt} 오름차순, 같으면 이력 번호 순이고 번호는 응답에 주지 않는다.
 * 이력이 없으면 {@code items}는 {@code []}다. 값이 없는 필드({@code reason}·{@code before}·{@code after})는 생략하지 않고
 * {@code null}로 준다(08 예시와 같다).
 *
 * @param items 이력 목록
 */
public record ReservationRevisionListResponse(List<Item> items) {

    /**
     * @param kind      {@code STATUS}·{@code MEMO}·{@code ASSIGNEE}
     * @param changedAt 이력이 남은 일시
     * @param changedBy 바꾼 사람
     * @param reason    사유. 없으면 null
     * @param changes   한 이력에 한 항목
     */
    public record Item(RevisionKind kind, LocalDateTime changedAt, Person changedBy, String reason, List<Change> changes) {
    }

    /** 바꾼 사람·담당. {@code name}은 그 계정의 현재 이름이다(비활성이어도 보인다). */
    public record Person(long staffId, String name) {
    }

    /**
     * 바뀐 항목. {@code STATUS}는 {@code status}(상태 이름), {@code MEMO}는 {@code purposeMemo}(메모, 없으면 null),
     * {@code ASSIGNEE}는 {@code assignee}({@link Person})다.
     */
    public record Change(String field, Object before, Object after) {
    }
}
