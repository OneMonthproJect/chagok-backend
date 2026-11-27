package com.wanted.chagok.domain.maintenance.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API 38 응답 (08 38번). {@code revisionNo} 오름차순(오래된 것부터), 쪽 나누기 없음. 이력이 없으면 {@code items}는 {@code []}다.
 * 값이 없는 필드({@code reason}·{@code before}·{@code after})는 생략하지 않고 {@code null}로 준다(08 예시와 같다).
 */
public record RecordRevisionListResponse(List<Item> items) {

    /**
     * @param revisionNo 그 기록의 수정 번호, 1부터
     * @param changedAt  수정한 일시
     * @param changedBy  수정한 사람
     * @param reason     수정 사유. 없으면 null
     * @param changes    그 수정에서 바뀐 필드마다 한 항목 (recordDate, mileage, type, item, inspectResult, memo, mechanic 순서)
     */
    public record Item(int revisionNo, LocalDateTime changedAt, Person changedBy, String reason, List<Change> changes) {
    }

    /** 사람·담당. {@code name}은 그 계정의 현재 이름이다(비활성이어도 보인다). */
    public record Person(long staffId, String name) {
    }

    /** {@code item} 필드의 값. {@code name}은 그 항목의 현재 이름이다. */
    public record ItemValue(long itemId, String name) {
    }

    /** 바뀐 필드 하나. {@code before}·{@code after}의 모양은 {@code field}마다 08 38번 표와 같다. */
    public record Change(String field, Object before, Object after) {
    }
}
