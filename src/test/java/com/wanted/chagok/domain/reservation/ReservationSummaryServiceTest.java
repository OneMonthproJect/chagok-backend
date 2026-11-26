package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.reservation.dto.ReservationSummaryResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationSummaryResponse.Item;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository.ReservedStatusRow;
import com.wanted.chagok.domain.reservation.service.ReservationSummaryService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API 42 월 요약의 집계와 기간 규칙 (08 7.1 42, 04 F-RSV-05, D-63 B05). DB 없이 돈다.
 * 정비소 격리·실제 SQL·상태 문자열 읽기는 {@code ReservationSummaryDbTest}.
 */
class ReservationSummaryServiceTest {

    private static final long SHOP = 3L;

    private final ReservationRepository repository = mock(ReservationRepository.class);
    private final ReservationSummaryService service = new ReservationSummaryService(repository);

    private record Row(LocalDateTime at, String state) implements ReservedStatusRow {
        @Override
        public LocalDateTime getReservedAt() {
            return at;
        }

        @Override
        public String getStatus() {
            return state;
        }
    }

    private static Row row(String dateTime, String status) {
        return new Row(LocalDateTime.parse(dateTime), status);
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private void rows(Row... rows) {
        when(repository.findStatusRowsInPeriod(anyLong(), any(), any())).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("날짜별 상태별 건수, total은 취소만 제외 — 진행중·노쇼는 total에 포함 (08 7.1 42 예시와 같은 값)")
    void countsByStatusAndTotalExcludesOnlyCanceled() {
        rows(
            row("2026-11-23T10:00:00", "RESERVED"), row("2026-11-23T11:00:00", "RESERVED"),
            row("2026-11-23T12:00:00", "COMPLETED"), row("2026-11-23T13:00:00", "CANCELED"),
            row("2026-11-24T09:30:00", "IN_PROGRESS"));

        ReservationSummaryResponse response = service.summarize(SHOP, d("2026-11-01"), d("2026-11-30"));

        assertThat(response.from()).isEqualTo(d("2026-11-01"));
        assertThat(response.to()).isEqualTo(d("2026-11-30"));
        assertThat(response.items()).containsExactly(
            new Item(d("2026-11-23"), 3, 2, 0, 1, 1, 0),
            new Item(d("2026-11-24"), 1, 0, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("노쇼는 total에 포함하고 noShow로 센다, 취소만 있는 날은 total 0")
    void noShowCountsInTotalAndCanceledOnlyDayIsZero() {
        rows(
            row("2026-11-20T10:00:00", "NO_SHOW"), row("2026-11-20T11:00:00", "RESERVED"),
            row("2026-11-21T10:00:00", "CANCELED"), row("2026-11-21T15:00:00", "CANCELED"));

        assertThat(service.summarize(SHOP, d("2026-11-20"), d("2026-11-21")).items()).containsExactly(
            new Item(d("2026-11-20"), 2, 1, 0, 0, 0, 1),
            new Item(d("2026-11-21"), 0, 0, 0, 0, 2, 0));
    }

    @Test
    @DisplayName("예약이 없는 날짜는 items에 없다, 예약이 아예 없으면 빈 목록(null 아님)")
    void emptyDaysAreAbsent() {
        rows();
        assertThat(service.summarize(SHOP, d("2026-11-01"), d("2026-11-30")).items()).isEmpty();

        rows(row("2026-11-15T10:00:00", "RESERVED"), row("2026-11-17T10:00:00", "RESERVED"));
        assertThat(service.summarize(SHOP, d("2026-11-01"), d("2026-11-30")).items())
            .extracting(Item::date).containsExactly(d("2026-11-15"), d("2026-11-17"));
    }

    @Test
    @DisplayName("items는 입력 순서와 무관하게 date 오름차순")
    void sortedByDate() {
        rows(row("2026-11-25T10:00:00", "RESERVED"), row("2026-11-02T10:00:00", "COMPLETED"), row("2026-11-13T10:00:00", "RESERVED"));

        assertThat(service.summarize(SHOP, d("2026-11-01"), d("2026-11-30")).items())
            .extracting(Item::date).containsExactly(d("2026-11-02"), d("2026-11-13"), d("2026-11-25"));
    }

    @Test
    @DisplayName("기간은 to 날짜를 포함해 읽는다: [from 00:00, to+1일 00:00) — 월 경계일 (1일·30일)")
    void periodIncludesToDay() {
        rows();

        service.summarize(SHOP, d("2026-11-01"), d("2026-11-30"));

        verify(repository).findStatusRowsInPeriod(SHOP, d("2026-11-01").atStartOfDay(), d("2026-12-01").atStartOfDay());
    }

    @Test
    @DisplayName("기간 규칙은 15와 같다: 42일은 통과, 43일·시작>끝은 400 INVALID_INPUT이고 조회하지 않는다")
    void periodRuleSameAs15() {
        rows();
        LocalDate from = d("2026-11-01");

        service.summarize(SHOP, from, from.plusDays(41)); // 42일
        assertThatThrownBy(() -> service.summarize(SHOP, from, from.plusDays(42))) // 43일
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("to");
            });
        assertThatThrownBy(() -> service.summarize(SHOP, d("2026-11-05"), d("2026-11-04")))
            .isInstanceOfSatisfying(BusinessException.class, e ->
                assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("from"));
    }

    @Test
    @DisplayName("기간 규칙 위반이면 조회하지 않는다")
    void invalidPeriodDoesNotQuery() {
        assertThatThrownBy(() -> service.summarize(SHOP, d("2026-11-05"), d("2026-11-04"))).isInstanceOf(BusinessException.class);

        verify(repository, never()).findStatusRowsInPeriod(anyLong(), any(), any());
    }

    @Test
    @DisplayName("조회는 그 정비소 shopId로만 한다")
    void queriesWithShopId() {
        rows();

        service.summarize(9L, d("2026-11-01"), d("2026-11-30"));

        verify(repository).findStatusRowsInPeriod(org.mockito.ArgumentMatchers.eq(9L), any(), any());
    }

    @Test
    @DisplayName("알 수 없는 상태 이름은 서버 버그로 드러낸다 (조용히 넘기지 않는다)")
    void unknownStatusIsServerBug() {
        rows(row("2026-11-23T10:00:00", "WEIRD"));

        assertThatThrownBy(() -> service.summarize(SHOP, d("2026-11-01"), d("2026-11-30")))
            .isInstanceOf(IllegalStateException.class);
    }
}
