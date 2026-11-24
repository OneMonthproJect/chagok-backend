package com.wanted.chagok.domain.cycle.service;

import com.wanted.chagok.domain.cycle.api.BaseRecord;
import com.wanted.chagok.domain.cycle.api.CycleRecord;
import com.wanted.chagok.domain.cycle.api.CycleRule;
import com.wanted.chagok.domain.cycle.api.DueReason;
import com.wanted.chagok.domain.cycle.api.ItemCycleCalculator;
import com.wanted.chagok.domain.cycle.api.ItemCycleResult;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 항목 상태 계산 본체 (04 F-CYC-01). 상태 판정은 이 클래스 한 곳에서만 한다 (CONVENTIONS 4장).
 *
 * <p>Spring·JPA·다른 도메인을 쓰지 않는 순수 계산이다. 현재 시각을 읽지 않고 {@code today}를 인자로 받는다.
 */
public class DefaultItemCycleCalculator implements ItemCycleCalculator {

    /** 임박 기준: 오늘 + 1개월 ≥ 다음 정비일 (04 F-CYC-01, D-08 달력상 1개월). 바꾸려면 이 상수와 T 테스트만 고친다 (CONVENTIONS 9장). */
    static final int IMMINENT_MONTHS = 1;

    /** 기준 기록 선택: 날짜가 가장 늦은 것, 같은 날이면 나중에 저장한 것({@code createdAt}), 그래도 같으면 {@code id}가 큰 것 (07 3.7). */
    private static final Comparator<CycleRecord> LATEST_FIRST_ORDER =
            Comparator.comparing(CycleRecord::date)
                    .thenComparing(CycleRecord::createdAt)
                    .thenComparingLong(CycleRecord::id);

    @Override
    public ItemCycleResult calculate(CycleRule rule, List<CycleRecord> records, LocalDate today, Integer visitMileage) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(today, "today");

        // 근거: 04 §6 F-CYC-01 "기준 기록" — 교체·외부 중 가장 늦은 것. 점검·거절은 기준이 아님
        Optional<CycleRecord> base = records.stream()
                .filter(CycleRecord::resetsCycle)
                .max(LATEST_FIRST_ORDER);
        if (base.isEmpty()) {
            return new ItemCycleResult(ItemStatus.UNKNOWN, null, null, null, null);
        }

        CycleRecord baseRecord = base.get();
        // 근거: 04 §6 "다음 정비일" — 달력 기준 plusMonths, 말일은 그 달 말일로 (T6·T7)
        LocalDate nextDueDate = baseRecord.date().plusMonths(rule.cycleMonths());
        // km 판정 생략: 기준 기록에 주행거리가 없으면 다음 km도 없다 (T10)
        Integer nextDueMileage = baseRecord.mileage() == null ? null : baseRecord.mileage() + rule.cycleKm();

        // 근거: 04 §6 "도래" — 오늘 ≥ 다음 정비일 (경계 포함, T5)
        boolean dueByDate = !today.isBefore(nextDueDate);
        // 근거: 04 §6 "도래" — 방문 주행거리 ≥ 기준 km + 주기 km (경계 포함, D-09, T8). 입력이 없거나 기준 km가 없으면 생략 (T10·T11)
        boolean dueByKm = visitMileage != null && nextDueMileage != null && visitMileage >= nextDueMileage;

        ItemStatus status;
        DueReason dueReason = null;
        if (dueByDate || dueByKm) {
            status = ItemStatus.DUE;
            dueReason = dueByDate ? DueReason.DATE : DueReason.KM; // 둘 다면 DATE (08 10번)
        } else if (!today.plusMonths(IMMINENT_MONTHS).isBefore(nextDueDate)) {
            status = ItemStatus.SOON; // 근거: 04 §6 "임박" (경계 포함, T3·T4). km 판정은 도래만 만든다
        } else {
            status = ItemStatus.AHEAD;
        }

        return new ItemCycleResult(
                status,
                dueReason,
                new BaseRecord(baseRecord.date(), baseRecord.mileage(), baseRecord.type()),
                nextDueDate,
                nextDueMileage);
    }
}
