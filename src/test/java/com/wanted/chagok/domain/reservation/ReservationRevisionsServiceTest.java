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
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.reservation.dto.ReservationRevisionListResponse;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationRevision;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.entity.RevisionKind;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRevisionRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * API 41 예약 이력 조회 (08 7.1 41번, X14): 응답 모양(종류별 {@code changes}), 이름 채우기, 이력 없음, 남의 예약 404. Repository는 가짜(DB 없음).
 * 정렬은 쿼리 이름이 정하므로(created_at, id 오름차순) 실제 순서는 {@code ReservationCorrectionDbTest}가 본다.
 */
class ReservationRevisionsServiceTest {

    private static final long SHOP = 3L;
    private static final long RES_ID = 5L;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);
    private final ReservationRepository repository = mock(ReservationRepository.class);
    private final ReservationRevisionRepository revisions = mock(ReservationRevisionRepository.class);
    private final StaffReader staffReader = mock(StaffReader.class);
    private final ReservationService service = new ReservationService(repository, mock(ReservationItemRepository.class),
        revisions, mock(CustomerReader.class), mock(CustomerWriter.class), mock(ItemReader.class), staffReader, clock);

    private void owned() {
        Reservation r = Reservation.register(SHOP, 4L, LocalDateTime.of(2026, 11, 20, 10, 0), false, null, 8L, 7L);
        ReflectionTestUtils.setField(r, "id", RES_ID);
        ReflectionTestUtils.setField(r, "status", ReservationStatus.COMPLETED);
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.of(r));
    }

    private static ReservationRevision at(ReservationRevision r, LocalDateTime when) {
        ReflectionTestUtils.setField(r, "createdAt", when);
        return r;
    }

    @Test
    @DisplayName("종류별 changes: STATUS는 status(상태 이름), MEMO는 purposeMemo(없던 메모는 null), ASSIGNEE는 assignee({staffId,name}) — 바꾼 사람 이름과 함께")
    void mapsEachKind() {
        owned();
        when(revisions.findByShopIdAndReservationIdOrderByCreatedAtAscIdAsc(SHOP, RES_ID)).thenReturn(List.of(
            at(ReservationRevision.assigneeChanged(SHOP, RES_ID, 7L, null, 8L, 7L), LocalDateTime.of(2026, 11, 21, 16, 20)),
            at(ReservationRevision.statusCorrected(SHOP, RES_ID, 7L, "고객 전화", ReservationStatus.NO_SHOW, ReservationStatus.CANCELED),
                LocalDateTime.of(2026, 11, 25, 9, 10)),
            at(ReservationRevision.memoCorrected(SHOP, RES_ID, 7L, "고객 전화", null, "배터리 교체"), LocalDateTime.of(2026, 11, 25, 9, 10))));
        when(staffReader.findNames(any(Long.class), any())).thenReturn(Map.of(7L, "박사장", 8L, "김정비"));

        ReservationRevisionListResponse response = service.revisions(SHOP, RES_ID);

        assertThat(response.items()).extracting(ReservationRevisionListResponse.Item::kind)
            .containsExactly(RevisionKind.ASSIGNEE, RevisionKind.STATUS, RevisionKind.MEMO);
        ReservationRevisionListResponse.Item assignee = response.items().get(0);
        assertThat(assignee.changedAt()).isEqualTo(LocalDateTime.of(2026, 11, 21, 16, 20));
        assertThat(assignee.changedBy()).isEqualTo(new ReservationRevisionListResponse.Person(7L, "박사장"));
        assertThat(assignee.reason()).isNull();
        assertThat(assignee.changes()).containsExactly(new ReservationRevisionListResponse.Change("assignee",
            new ReservationRevisionListResponse.Person(8L, "김정비"), new ReservationRevisionListResponse.Person(7L, "박사장")));
        assertThat(response.items().get(1).reason()).isEqualTo("고객 전화");
        assertThat(response.items().get(1).changes())
            .containsExactly(new ReservationRevisionListResponse.Change("status", "NO_SHOW", "CANCELED"));
        assertThat(response.items().get(2).changes())
            .containsExactly(new ReservationRevisionListResponse.Change("purposeMemo", null, "배터리 교체"));
    }

    @Test
    @DisplayName("이름은 한 번에 읽는다 — 이력 여러 건이어도 findNames 한 번, 조회는 토큰의 정비소로만")
    void readsNamesOnce() {
        owned();
        when(revisions.findByShopIdAndReservationIdOrderByCreatedAtAscIdAsc(SHOP, RES_ID)).thenReturn(List.of(
            at(ReservationRevision.statusCorrected(SHOP, RES_ID, 7L, null, ReservationStatus.NO_SHOW, ReservationStatus.CANCELED),
                LocalDateTime.of(2026, 11, 25, 9, 0)),
            at(ReservationRevision.statusCorrected(SHOP, RES_ID, 7L, null, ReservationStatus.CANCELED, ReservationStatus.COMPLETED),
                LocalDateTime.of(2026, 11, 25, 10, 0))));
        when(staffReader.findNames(any(Long.class), any())).thenReturn(Map.of(7L, "박사장"));

        service.revisions(SHOP, RES_ID);

        verify(staffReader).findNames(SHOP, java.util.Set.of(7L));
    }

    @Test
    @DisplayName("이력이 없으면 items는 [] 이고 이름 조회를 하지 않는다")
    void noRevisions() {
        owned();
        when(revisions.findByShopIdAndReservationIdOrderByCreatedAtAscIdAsc(SHOP, RES_ID)).thenReturn(List.of());

        assertThat(service.revisions(SHOP, RES_ID).items()).isEmpty();

        verify(staffReader, never()).findNames(anyLong(), any());
    }

    @Test
    @DisplayName("X14 다른 정비소·없는 예약 → 404 NOT_FOUND, 이력 조회 없음")
    void notOwned() {
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revisions(SHOP, RES_ID)).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(revisions, never()).findByShopIdAndReservationIdOrderByCreatedAtAscIdAsc(anyLong(), anyLong());
    }
}
