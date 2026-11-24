package com.wanted.chagok.domain.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.cycle.api.BaseRecord;
import com.wanted.chagok.domain.cycle.api.CycleRecord;
import com.wanted.chagok.domain.cycle.api.CycleRule;
import com.wanted.chagok.domain.cycle.api.DueReason;
import com.wanted.chagok.domain.cycle.api.ItemCycleCalculator;
import com.wanted.chagok.domain.cycle.api.ItemCycleResult;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 04 「주기 계산 테스트 (T-xx)」 T1~T13 (P0-핵심). 기준: 엔진오일 6개월/10,000km, 에어컨필터 6개월/10,000km (D-01).
 * 오늘은 인자로 고정한다. DB·Spring 없이 돈다.
 */
class ItemCycleCalculatorTest {

    private static final CycleRule RULE = new CycleRule(6, 10_000);

    private final ItemCycleCalculator calculator = ItemCycleCalculator.standard();

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private static CycleRecord replace(LocalDate date, Integer mileage) {
        return record(date, mileage, true, "REPLACE", date.atTime(10, 0));
    }

    private static CycleRecord external(LocalDate date, Integer mileage) {
        return record(date, mileage, true, "EXTERNAL", date.atTime(10, 0));
    }

    private static CycleRecord inspect(LocalDate date) {
        return record(date, null, false, "INSPECT", date.atTime(10, 0));
    }

    private static CycleRecord decline(LocalDate date) {
        return record(date, null, false, "DECLINE", date.atTime(10, 0));
    }

    private static CycleRecord record(LocalDate date, Integer mileage, boolean resetsCycle, String type, LocalDateTime createdAt) {
        return new CycleRecord(date, mileage, resetsCycle, createdAt, NEXT_ID.getAndIncrement(), type);
    }

    private ItemCycleResult calc(List<CycleRecord> records, LocalDate today, Integer visitMileage) {
        return calculator.calculate(RULE, records, today, visitMileage);
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    // ---------- T1~T13 ----------

    @Test
    void T1_기록_없음은_미확인() {
        ItemCycleResult result = calc(List.of(), d("2026-09-01"), null);

        assertThat(result.status()).isEqualTo(ItemStatus.UNKNOWN);
        assertThat(result.dueReason()).isNull();
        assertThat(result.baseRecord()).isNull();
        assertThat(result.nextDueDate()).isNull();
        assertThat(result.nextDueMileage()).isNull();
    }

    @Test
    void T1_점검_거절_기록만_있어도_미확인() {
        ItemCycleResult result = calc(List.of(inspect(d("2026-05-01")), decline(d("2026-06-01"))), d("2026-09-01"), 99_999);

        assertThat(result.status()).isEqualTo(ItemStatus.UNKNOWN);
        assertThat(result.baseRecord()).isNull();
    }

    @Test
    void T2_교체_후_여유() {
        ItemCycleResult result = calc(List.of(replace(d("2026-05-10"), null)), d("2026-09-01"), null);

        assertThat(result.status()).isEqualTo(ItemStatus.AHEAD);
        assertThat(result.dueReason()).isNull();
        assertThat(result.nextDueDate()).isEqualTo(d("2026-11-10"));
    }

    @Test
    void T3_임박_경계_정확히_1개월() {
        // 다음 정비일 2026-04-15 (교체 2025-10-15 + 6개월), 오늘 2026-03-15
        ItemCycleResult result = calc(List.of(replace(d("2025-10-15"), null)), d("2026-03-15"), null);

        assertThat(result.nextDueDate()).isEqualTo(d("2026-04-15"));
        assertThat(result.status()).isEqualTo(ItemStatus.SOON);
    }

    @Test
    void T4_임박_하루_전은_여유() {
        ItemCycleResult result = calc(List.of(replace(d("2025-10-15"), null)), d("2026-03-14"), null);

        assertThat(result.nextDueDate()).isEqualTo(d("2026-04-15"));
        assertThat(result.status()).isEqualTo(ItemStatus.AHEAD);
    }

    @Test
    void T5_다음_정비일_당일은_도래_DATE() {
        ItemCycleResult result = calc(List.of(replace(d("2025-10-15"), null)), d("2026-04-15"), null);

        assertThat(result.status()).isEqualTo(ItemStatus.DUE);
        assertThat(result.dueReason()).isEqualTo(DueReason.DATE);
    }

    @Test
    void T6_말일_처리_8월31일에_6개월은_2월말일() {
        ItemCycleResult result = calc(List.of(replace(d("2025-08-31"), null)), d("2025-09-01"), null);

        assertThat(result.nextDueDate()).isEqualTo(d("2026-02-28"));
    }

    @Test
    void T7_말일_처리_윤년_2월은_29일() {
        ItemCycleResult result = calc(List.of(replace(d("2027-08-31"), null)), d("2027-09-01"), null);

        assertThat(result.nextDueDate()).isEqualTo(d("2028-02-29"));
    }

    @Test
    void T8_km_경계_포함은_도래_KM() {
        // 교체 45,000km, 날짜로는 여유. 방문 주행거리 55,000km = 기준 + 주기
        ItemCycleResult result = calc(List.of(replace(d("2026-09-24"), 45_000)), d("2026-11-24"), 55_000);

        assertThat(result.status()).isEqualTo(ItemStatus.DUE);
        assertThat(result.dueReason()).isEqualTo(DueReason.KM);
        assertThat(result.nextDueMileage()).isEqualTo(55_000);
    }

    @Test
    void T9_km_1_모자라면_날짜_기준_유지() {
        ItemCycleResult result = calc(List.of(replace(d("2026-09-24"), 45_000)), d("2026-11-24"), 54_999);

        assertThat(result.status()).isEqualTo(ItemStatus.AHEAD);
        assertThat(result.dueReason()).isNull();
    }

    @Test
    void T10_기준_기록에_주행거리가_없으면_km_판정_생략() {
        ItemCycleResult result = calc(List.of(replace(d("2026-09-24"), null)), d("2026-11-24"), 99_999);

        assertThat(result.status()).isEqualTo(ItemStatus.AHEAD);
        assertThat(result.nextDueMileage()).isNull();
    }

    @Test
    void T11_방문_주행거리를_입력하지_않으면_날짜_기준만() {
        ItemCycleResult result = calc(List.of(replace(d("2026-09-24"), 45_000)), d("2026-11-24"), null);

        assertThat(result.status()).isEqualTo(ItemStatus.AHEAD);
        assertThat(result.nextDueMileage()).isEqualTo(55_000); // 방문 km가 없어도 근거(기준 km + 주기 km)는 남는다
    }

    @Test
    void T12_점검_거절은_기준_기록이_아니다() {
        ItemCycleResult result = calc(
                List.of(replace(d("2026-01-10"), 40_000), inspect(d("2026-06-01")), decline(d("2026-06-15"))),
                d("2026-06-20"),
                null);

        assertThat(result.baseRecord()).isEqualTo(new BaseRecord(d("2026-01-10"), 40_000, "REPLACE"));
        assertThat(result.nextDueDate()).isEqualTo(d("2026-07-10"));
    }

    @Test
    void T13_외부_기록이_더_늦으면_주기가_다시_시작된다() {
        ItemCycleResult result = calc(
                List.of(replace(d("2025-12-01"), 30_000), external(d("2026-06-01"), null)),
                d("2026-06-20"),
                null);

        assertThat(result.baseRecord()).isEqualTo(new BaseRecord(d("2026-06-01"), null, "EXTERNAL"));
        assertThat(result.nextDueDate()).isEqualTo(d("2026-12-01"));
        assertThat(result.status()).isEqualTo(ItemStatus.AHEAD);
    }

    // ---------- 이슈 #19 추가 경계 (말일·km 경계 등) ----------

    @Test
    void 임박_경계도_말일은_그_달_말일로_계산한다() {
        // 오늘 1/31 + 1개월 = 2/28. 다음 정비일이 2/28이면 임박(경계 포함)
        ItemCycleResult result = calc(List.of(replace(d("2025-08-28"), null)), d("2026-01-31"), null);

        assertThat(result.nextDueDate()).isEqualTo(d("2026-02-28"));
        assertThat(result.status()).isEqualTo(ItemStatus.SOON);
    }

    @Test
    void km_기준이_임박을_만들지는_않는다() {
        // km가 거의 다 찼어도(54,999) 날짜가 여유면 임박이 아니라 여유
        ItemCycleResult result = calc(List.of(replace(d("2026-09-24"), 45_000)), d("2026-11-24"), 54_999);

        assertThat(result.status()).isEqualTo(ItemStatus.AHEAD);
    }

    @Test
    void 날짜와_km가_모두_도래면_이유는_DATE() {
        ItemCycleResult result = calc(List.of(replace(d("2025-10-15"), 45_000)), d("2026-04-15"), 60_000);

        assertThat(result.status()).isEqualTo(ItemStatus.DUE);
        assertThat(result.dueReason()).isEqualTo(DueReason.DATE);
    }

    @Test
    void 날짜가_도래면_방문_주행거리가_없어도_도래() {
        ItemCycleResult result = calc(List.of(replace(d("2025-10-15"), 45_000)), d("2026-05-01"), null);

        assertThat(result.status()).isEqualTo(ItemStatus.DUE);
        assertThat(result.dueReason()).isEqualTo(DueReason.DATE);
    }

    @Test
    void 같은_날_기록이_둘이면_나중에_저장한_것이_기준() {
        CycleRecord morning = record(d("2026-05-10"), 40_000, true, "REPLACE", LocalDateTime.parse("2026-05-10T09:00:00"));
        CycleRecord afternoon = record(d("2026-05-10"), 41_000, true, "REPLACE", LocalDateTime.parse("2026-05-10T15:00:00"));

        ItemCycleResult result = calc(List.of(afternoon, morning), d("2026-06-01"), null);

        assertThat(result.baseRecord().mileage()).isEqualTo(41_000);
    }

    @Test
    void 같은_날_같은_저장_시각이면_id가_큰_것이_기준() {
        LocalDateTime sameTime = LocalDateTime.parse("2026-05-10T09:00:00");
        CycleRecord smallId = new CycleRecord(d("2026-05-10"), 40_000, true, sameTime, 1L, "REPLACE");
        CycleRecord bigId = new CycleRecord(d("2026-05-10"), 41_000, true, sameTime, 2L, "REPLACE");

        ItemCycleResult result = calc(List.of(bigId, smallId), d("2026-06-01"), null);

        assertThat(result.baseRecord().mileage()).isEqualTo(41_000);
    }

    @Test
    void 기록_목록_순서와_관계없이_같은_결과() {
        CycleRecord older = replace(d("2025-12-01"), 30_000);
        CycleRecord newer = external(d("2026-06-01"), 50_000);

        assertThat(calc(List.of(older, newer), d("2026-06-20"), null))
                .isEqualTo(calc(List.of(newer, older), d("2026-06-20"), null));
    }

    // ---------- 계약 값 타입의 검증 ----------

    @Test
    void 결과_dueReason은_도래일_때만_값이_있다() {
        assertThatThrownBy(() -> new ItemCycleResult(ItemStatus.AHEAD, DueReason.DATE, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCycleResult(ItemStatus.DUE, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 결과_미확인은_근거_값이_없어야_한다() {
        assertThatThrownBy(() -> new ItemCycleResult(ItemStatus.UNKNOWN, null, null, d("2026-11-10"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 주기는_0보다_커야_한다() {
        assertThatThrownBy(() -> new CycleRule(0, 10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CycleRule(6, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
