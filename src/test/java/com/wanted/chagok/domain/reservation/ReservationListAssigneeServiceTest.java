package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.reservation.dto.ReservationListResponse;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRevisionRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API 15의 {@code assigneeId} 쿼리 분기 ("내 담당만" 보기, P0-나머지, 08 15번): 있으면 그 담당 조회, 없으면 기존 조회.
 * 기간 규칙은 둘 다 같다. DB 없이 돈다. 정비소 조건·실제 SQL은 {@code ReservationSummaryDbTest}.
 */
class ReservationListAssigneeServiceTest {

    private static final long SHOP = 3L;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 23);

    private final ReservationRepository repository = mock(ReservationRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);
    private final ReservationService service = new ReservationService(
        repository, mock(ReservationItemRepository.class), mock(ReservationRevisionRepository.class), mock(CustomerReader.class), mock(ItemReader.class), mock(StaffReader.class), clock);

    @Test
    @DisplayName("assigneeId가 있으면 그 담당의 예약만 조회한다 (정비소 조건 포함, 기존 조회는 부르지 않는다)")
    void filtersByAssignee() {
        when(repository.findInPeriodByAssignee(SHOP, 2L, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay())).thenReturn(List.of());

        ReservationListResponse response = service.list(SHOP, DAY, DAY, 2L);

        assertThat(response.items()).isEmpty();
        verify(repository).findInPeriodByAssignee(SHOP, 2L, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay());
        verify(repository, never()).findInPeriod(anyLong(), any(), any());
    }

    @Test
    @DisplayName("assigneeId가 없으면(null) 기존처럼 정비소 전체를 조회한다")
    void withoutAssigneeIsUnchanged() {
        when(repository.findInPeriod(SHOP, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay())).thenReturn(List.of());

        service.list(SHOP, DAY, DAY, null);
        service.list(SHOP, DAY, DAY); // 3개 인자 호출도 같다

        verify(repository, org.mockito.Mockito.times(2)).findInPeriod(SHOP, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay());
        verify(repository, never()).findInPeriodByAssignee(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("assigneeId를 줘도 기간 규칙(42일·시작>끝)은 같고, 위반이면 조회하지 않는다")
    void periodRuleStillApplies() {
        assertThatThrownBy(() -> service.list(SHOP, DAY, DAY.plusDays(42), 2L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(SHOP, DAY.plusDays(1), DAY, 2L)).isInstanceOf(BusinessException.class);

        verify(repository, never()).findInPeriodByAssignee(anyLong(), anyLong(), any(), any());
    }
}
