package com.wanted.chagok.domain.cycle.service;

import com.wanted.chagok.domain.cycle.api.BaseRecord;
import com.wanted.chagok.domain.cycle.api.CycleNote;
import com.wanted.chagok.domain.cycle.api.CycleNoteRecord;
import com.wanted.chagok.domain.cycle.api.CycleNotes;
import com.wanted.chagok.domain.cycle.api.CycleRecord;
import com.wanted.chagok.domain.cycle.api.CycleRule;
import com.wanted.chagok.domain.cycle.api.DueReason;
import com.wanted.chagok.domain.cycle.api.ItemCycleCalculator;
import com.wanted.chagok.domain.cycle.api.ItemCycleResult;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * 항목 상태 계산 본체 (04 F-CYC-01). 상태 판정은 이 클래스 한 곳에서만 한다 (CONVENTIONS 4장).
 *
 * <p>Spring·JPA·다른 도메인을 쓰지 않는 순수 계산이다. 현재 시각을 읽지 않고 {@code today}를 인자로 받는다.
 */
public class DefaultItemCycleCalculator implements ItemCycleCalculator {

    /** 임박 기준: 오늘 + 1개월 ≥ 다음 정비일 (04 F-CYC-01, D-08 달력상 1개월). 바꾸려면 이 상수와 T 테스트만 고친다 (CONVENTIONS 9장). */
    static final int IMMINENT_MONTHS = 1;

    /**
     * "더 최근" 순서의 단일 정의: 날짜가 늦은 것, 같은 날이면 나중에 저장한 것({@code createdAt}), 그래도 같으면 {@code id}가
     * 큰 것 (07 3.7). 상태 계산의 기준 기록 선택과 표시 판정의 가장 최근 기록 선택이 같은 순서를 쓰도록 한 곳에만 둔다.
     */
    private static <T> Comparator<T> recency(
            Function<T, LocalDate> date, Function<T, LocalDateTime> createdAt, ToLongFunction<T> id) {
        return Comparator.comparing(date).thenComparing(createdAt).thenComparingLong(id);
    }

    private static final Comparator<CycleRecord> LATEST_FIRST_ORDER =
            recency(CycleRecord::date, CycleRecord::createdAt, CycleRecord::id);

    private static final Comparator<CycleNoteRecord> NOTE_LATEST_FIRST_ORDER =
            recency(CycleNoteRecord::date, CycleNoteRecord::createdAt, CycleNoteRecord::id);

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

    @Override
    public CycleNotes judgeNotes(List<CycleNoteRecord> records) {
        Objects.requireNonNull(records, "records");

        // 근거: 04 F-CYC-02 — 두 표시 모두 "그 항목의 가장 최근 기록" 하나만 본다. 이후 같은 항목이 다시 기록되면
        // 가장 최근 기록이 바뀌어 앞선 표시가 사라진다 (구분 무관)
        Optional<CycleNoteRecord> latest = records.stream().max(NOTE_LATEST_FIRST_ORDER);
        if (latest.isEmpty()) {
            return CycleNotes.NONE;
        }
        CycleNoteRecord last = latest.get();

        return switch (last.kind()) {
            // 근거: 04 F-CYC-02 "지난 점검 주의" — 가장 최근 기록이 점검이고 결과가 주의 (T15)
            case INSPECT_CAUTION -> new CycleNotes(null, new CycleNote(last.date(), last.memo()));
            // 근거: 04 F-CYC-02 "지난 방문 거절" — 가장 최근 기록이 거절이고 기준 기록보다 늦음 (T14). 거절은 주기를 다시
            // 시작하지 않는 기록이라 기준 기록이 될 수 없고, 전체에서 가장 최근이므로 기준 기록보다 늦다는 조건은 이 선택에 이미
            // 포함된다. 기준 기록이 아직 없고 거절만 있어도 표시한다(04에 정의가 없는 경우의 결정: 표시. 이때 상태는 미확인이라
            // 14번 대상 목록에는 오르지 않는다). 상태·주기는 건드리지 않는다
            case DECLINE -> new CycleNotes(new CycleNote(last.date(), last.memo()), null);
            case OTHER -> CycleNotes.NONE;
        };
    }
}
