package com.wanted.chagok.domain.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.cycle.api.CycleNote;
import com.wanted.chagok.domain.cycle.api.CycleNoteKind;
import com.wanted.chagok.domain.cycle.api.CycleNoteRecord;
import com.wanted.chagok.domain.cycle.api.CycleNotes;
import com.wanted.chagok.domain.cycle.api.CycleRecord;
import com.wanted.chagok.domain.cycle.api.CycleRule;
import com.wanted.chagok.domain.cycle.api.ItemCycleCalculator;
import com.wanted.chagok.domain.cycle.api.ItemCycleResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 04 F-CYC-02 지난 방문 거절·지난 점검 주의 표시 (T14·T15). 두 표시는 "그 항목의 가장 최근 기록" 하나에서 나오고,
 * 상태 계산(F-CYC-01)을 바꾸지 않는다. 오늘이 필요 없는 순수 판정이라 DB·Spring 없이 돈다.
 */
class ItemCycleNotesTest {

    private final ItemCycleCalculator calculator = ItemCycleCalculator.standard();

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static CycleNoteRecord replace(String date) {
        return note(date, true, CycleNoteKind.OTHER, null, d(date).atTime(10, 0), NEXT_ID.getAndIncrement());
    }

    private static CycleNoteRecord external(String date) {
        return replace(date);
    }

    private static CycleNoteRecord inspectGood(String date) {
        return note(date, false, CycleNoteKind.OTHER, "이상 없음", d(date).atTime(10, 0), NEXT_ID.getAndIncrement());
    }

    private static CycleNoteRecord inspectCaution(String date, String memo) {
        return note(date, false, CycleNoteKind.INSPECT_CAUTION, memo, d(date).atTime(10, 0), NEXT_ID.getAndIncrement());
    }

    private static CycleNoteRecord decline(String date, String memo) {
        return note(date, false, CycleNoteKind.DECLINE, memo, d(date).atTime(10, 0), NEXT_ID.getAndIncrement());
    }

    private static CycleNoteRecord note(
            String date, boolean resetsCycle, CycleNoteKind kind, String memo, LocalDateTime createdAt, long id) {
        return new CycleNoteRecord(d(date), createdAt, id, resetsCycle, kind, memo);
    }

    private CycleNotes judge(CycleNoteRecord... records) {
        return calculator.judgeNotes(List.of(records));
    }

    // ---------- T14 지난 방문 거절 ----------

    @Test
    void T14_교체_뒤_거절이_가장_최근이면_지난_방문_거절() {
        CycleNotes notes = judge(replace("2026-03-01"), decline("2026-05-10", "비싸다고 거절"));

        assertThat(notes.lastDecline()).isEqualTo(new CycleNote(d("2026-05-10"), "비싸다고 거절"));
        assertThat(notes.lastCaution()).isNull();
    }

    @Test
    void T14_거절은_주기와_상태를_바꾸지_않는다() {
        CycleRule rule = new CycleRule(6, 10_000);
        CycleRecord base = new CycleRecord(d("2026-03-01"), 50_000, true, d("2026-03-01").atTime(10, 0), 1, "REPLACE");
        CycleRecord declined = new CycleRecord(d("2026-05-10"), null, false, d("2026-05-10").atTime(10, 0), 2, "DECLINE");
        LocalDate today = d("2026-09-01"); // 다음 정비일 2026-09-01 → 도래

        ItemCycleResult withDecline = calculator.calculate(rule, List.of(base, declined), today, null);
        ItemCycleResult withoutDecline = calculator.calculate(rule, List.of(base), today, null);

        assertThat(withDecline).isEqualTo(withoutDecline);
        assertThat(withDecline.nextDueDate()).isEqualTo(d("2026-09-01"));
    }

    @Test
    void T14_거절_뒤_같은_항목이_다시_기록되면_사라진다() {
        assertThat(judge(replace("2026-03-01"), decline("2026-05-10", null), replace("2026-06-01")))
                .isEqualTo(CycleNotes.NONE);
        assertThat(judge(replace("2026-03-01"), decline("2026-05-10", null), inspectGood("2026-06-01")))
                .isEqualTo(CycleNotes.NONE);
        assertThat(judge(replace("2026-03-01"), decline("2026-05-10", null), external("2026-04-01")))
                .as("외부 기록의 날짜(고객이 말한 정비일)가 거절보다 이르면 거절이 여전히 최근이다")
                .isEqualTo(new CycleNotes(new CycleNote(d("2026-05-10"), null), null));
    }

    @Test
    void T14_기준_기록이_아직_없고_거절만_있어도_표시한다() {
        CycleNotes notes = judge(decline("2026-05-10", "나중에"));

        assertThat(notes.lastDecline()).isEqualTo(new CycleNote(d("2026-05-10"), "나중에"));
        assertThat(notes.lastCaution()).isNull();
    }

    @Test
    void T14_거절이_연속이면_가장_최근_거절만_표시한다() {
        CycleNotes notes = judge(replace("2026-01-01"), decline("2026-04-01", "첫 번째"), decline("2026-07-01", "두 번째"));

        assertThat(notes.lastDecline()).isEqualTo(new CycleNote(d("2026-07-01"), "두 번째"));
    }

    // ---------- T15 지난 점검 주의 ----------

    @Test
    void T15_가장_최근_기록이_점검_주의면_지난_점검_주의_날짜와_메모() {
        CycleNotes notes = judge(replace("2026-03-01"), inspectCaution("2026-07-20", "패드 2mm 남음"));

        assertThat(notes.lastCaution()).isEqualTo(new CycleNote(d("2026-07-20"), "패드 2mm 남음"));
        assertThat(notes.lastDecline()).isNull();
    }

    @Test
    void T15_이후_같은_항목_교체_점검_외부_거절_어느_기록이든_표시가_사라진다() {
        CycleNoteRecord caution = inspectCaution("2026-07-20", "주의");

        assertThat(judge(replace("2026-03-01"), caution, replace("2026-08-01"))).isEqualTo(CycleNotes.NONE);
        assertThat(judge(replace("2026-03-01"), caution, inspectGood("2026-08-01"))).isEqualTo(CycleNotes.NONE);
        assertThat(judge(replace("2026-03-01"), caution, external("2026-08-01"))).isEqualTo(CycleNotes.NONE);
    }

    @Test
    void T15_점검_주의_뒤_거절이_오면_주의는_사라지고_거절만_표시한다() {
        CycleNotes notes = judge(replace("2026-03-01"), inspectCaution("2026-07-20", "주의"), decline("2026-08-05", "다음에"));

        assertThat(notes.lastCaution()).isNull();
        assertThat(notes.lastDecline()).isEqualTo(new CycleNote(d("2026-08-05"), "다음에"));
    }

    @Test
    void T15_점검_양호는_표시를_만들지_않는다() {
        assertThat(judge(replace("2026-03-01"), inspectGood("2026-07-20"))).isEqualTo(CycleNotes.NONE);
    }

    @Test
    void T15_기준_기록_없이_점검_주의만_있어도_표시한다_메모가_없어도() {
        CycleNotes notes = judge(inspectCaution("2026-07-20", null));

        assertThat(notes.lastCaution()).isEqualTo(new CycleNote(d("2026-07-20"), null));
    }

    // ---------- 경계·공통 ----------

    @Test
    void 기록이_없으면_표시가_없다() {
        assertThat(calculator.judgeNotes(List.of())).isEqualTo(CycleNotes.NONE);
        assertThat(CycleNotes.NONE.lastDecline()).isNull();
        assertThat(CycleNotes.NONE.lastCaution()).isNull();
    }

    @Test
    void 같은_날_기록은_나중에_저장한_것이_가장_최근이다() {
        String day = "2026-07-20";
        CycleNoteRecord earlier = note(day, false, CycleNoteKind.INSPECT_CAUTION, "먼저", d(day).atTime(9, 0), 10);
        CycleNoteRecord later = note(day, false, CycleNoteKind.DECLINE, "나중", d(day).atTime(15, 0), 11);

        assertThat(judge(replace("2026-03-01"), earlier, later).lastDecline()).isEqualTo(new CycleNote(d(day), "나중"));
        assertThat(judge(replace("2026-03-01"), later, earlier).lastDecline())
                .as("목록 순서와 무관하다")
                .isEqualTo(new CycleNote(d(day), "나중"));
    }

    @Test
    void 저장_시각까지_같으면_id가_큰_것이_가장_최근이다() {
        LocalDateTime same = d("2026-07-20").atTime(12, 0);
        CycleNoteRecord first = note("2026-07-20", false, CycleNoteKind.INSPECT_CAUTION, "id 5", same, 5);
        CycleNoteRecord second = note("2026-07-20", false, CycleNoteKind.DECLINE, "id 6", same, 6);

        assertThat(judge(first, second).lastDecline()).isEqualTo(new CycleNote(d("2026-07-20"), "id 6"));
        assertThat(judge(first, second).lastCaution()).isNull();
    }

    @Test
    void 같은_날_교체_뒤에_저장한_거절은_거절_직후라도_표시한다() {
        String day = "2026-07-20";
        CycleNoteRecord replaced = note(day, true, CycleNoteKind.OTHER, null, d(day).atTime(9, 0), 20);
        CycleNoteRecord declined = note(day, false, CycleNoteKind.DECLINE, "같은 날", d(day).atTime(9, 30), 21);

        assertThat(judge(replaced, declined).lastDecline()).isEqualTo(new CycleNote(d(day), "같은 날"));
        // 반대로 거절을 먼저 저장하고 같은 날 교체를 나중에 저장하면 교체가 가장 최근이라 사라진다
        CycleNoteRecord declinedFirst = note(day, false, CycleNoteKind.DECLINE, "먼저", d(day).atTime(9, 0), 22);
        CycleNoteRecord replacedLater = note(day, true, CycleNoteKind.OTHER, null, d(day).atTime(9, 30), 23);
        assertThat(judge(declinedFirst, replacedLater)).isEqualTo(CycleNotes.NONE);
    }

    @Test
    void 입력_목록_순서와_무관하다() {
        List<CycleNoteRecord> records = new ArrayList<>(List.of(
                replace("2026-01-01"), inspectCaution("2026-03-01", "주의"), decline("2026-05-01", "거절"),
                inspectGood("2026-02-01")));
        CycleNotes expected = calculator.judgeNotes(records);

        Collections.reverse(records);
        assertThat(calculator.judgeNotes(records)).isEqualTo(expected);
        assertThat(expected.lastDecline()).isEqualTo(new CycleNote(d("2026-05-01"), "거절"));
    }

    @Test
    void 입력_값_검증() {
        assertThatThrownBy(() -> calculator.judgeNotes(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CycleNoteRecord(d("2026-07-20"), d("2026-07-20").atTime(9, 0), 1, true, CycleNoteKind.DECLINE, null))
                .as("교체·외부(주기를 다시 시작)는 거절·점검 주의일 수 없다")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CycleNotes(new CycleNote(d("2026-07-20"), null), new CycleNote(d("2026-07-21"), null)))
                .as("거절과 점검 주의는 가장 최근 기록 하나에서만 나온다")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
