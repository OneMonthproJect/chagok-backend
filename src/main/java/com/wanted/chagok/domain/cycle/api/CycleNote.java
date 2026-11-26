package com.wanted.chagok.domain.cycle.api;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 표시 한 건. 필드는 08 10·14·17 응답의 {@code lastDecline}·{@code lastCaution} {@code { date, memo }}와 같은 뜻이라
 * 호출하는 쪽은 값을 그대로 옮기기만 한다. 표시를 다시 계산하지 않는다 (CONVENTIONS 4장).
 *
 * @param date 표시가 가리키는 기록일
 * @param memo 기록 메모. 없으면 null
 */
public record CycleNote(LocalDate date, String memo) {

    public CycleNote {
        Objects.requireNonNull(date, "date");
    }
}
