package com.wanted.chagok.domain.cycle.api;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 항목 하나의 상태 계산 결과. 필드는 08 10번 응답의 {@code status}·{@code dueReason}·{@code baseRecord}·
 * {@code nextDueDate}·{@code nextDueMileage}와 같은 뜻이라, 호출하는 쪽은 값을 그대로 옮기기만 하면 된다.
 * 상태를 다시 계산하지 않는다 (CONVENTIONS 4장).
 *
 * @param status          상태
 * @param dueReason       도래 이유. 상태가 {@code DUE}일 때만 값이 있고 아니면 null
 * @param baseRecord      기준 기록. 미확인이면 null
 * @param nextDueDate     다음 정비일 (기준일 + 주기 개월). 미확인이면 null
 * @param nextDueMileage  기준 km + 주기 km. 기준 기록에 km가 없으면 null
 */
public record ItemCycleResult(
        ItemStatus status,
        DueReason dueReason,
        BaseRecord baseRecord,
        LocalDate nextDueDate,
        Integer nextDueMileage) {

    public ItemCycleResult {
        Objects.requireNonNull(status, "status");
        if ((status == ItemStatus.DUE) != (dueReason != null)) {
            throw new IllegalArgumentException("dueReason은 DUE일 때만 값이 있다 (08 10번): status=" + status + ", dueReason=" + dueReason);
        }
        if (status == ItemStatus.UNKNOWN && (baseRecord != null || nextDueDate != null || nextDueMileage != null)) {
            throw new IllegalArgumentException("UNKNOWN은 기준 기록·다음 정비일·다음 km가 없다 (04 F-CYC-01)");
        }
    }
}
