package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.AssigneeRef;
import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.reservation.api.ReservationAssignee;
import com.wanted.chagok.domain.reservation.api.ReservationDay;
import com.wanted.chagok.domain.reservation.api.ReservationDetail;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationListResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRevisionRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 예약 응답의 담당 정비사 {@code assignee { staffId, name, colorIndex }} (08 15·04·17·16·19번, P0-나머지, #89). 이름과
 * {@code colorIndex}는 {@code account.api}(API 36)에서 한 번에 가져와 옮겨 담는다 — 계산은 account 한 곳이다(CONVENTIONS 4장).
 * Repository·다른 도메인 계약은 가짜(DB 없음). 실제 SQL·HTTP 응답은 {@code ReservationAssigneeResponseDbTest}.
 */
class ReservationAssigneeResponseServiceTest {

    private static final long SHOP = 3L;
    private static final long OWNER = 7L;
    private static final long MECHANIC = 8L;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    private final ReservationRepository repository = mock(ReservationRepository.class);
    private final ReservationItemRepository itemRepository = mock(ReservationItemRepository.class);
    private final CustomerReader customerReader = mock(CustomerReader.class);
    private final CustomerWriter customerWriter = mock(CustomerWriter.class);
    private final ReservationRevisionRepository revisionRepository = mock(ReservationRevisionRepository.class);
    private final ItemReader itemReader = mock(ItemReader.class);
    private final StaffReader staffReader = mock(StaffReader.class);
    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);
    private final ReservationService service =
        new ReservationService(repository, itemRepository, revisionRepository, customerReader, customerWriter, itemReader, staffReader, clock);

    private static final ReservationAssignee OWNER_VIEW = new ReservationAssignee(OWNER, "박사장", 0);
    private static final ReservationAssignee MECHANIC_VIEW = new ReservationAssignee(MECHANIC, "김정비", 1);

    private void candidates() {
        when(staffReader.findAssignees(SHOP)).thenReturn(List.of(
            new AssigneeRef(OWNER, "박사장", StaffRole.OWNER, 0), new AssigneeRef(MECHANIC, "김정비", StaffRole.MECHANIC, 1)));
    }

    private static Reservation reservation(long id, long vehicleId, LocalDateTime at, long assignee) {
        Reservation r = Reservation.register(SHOP, vehicleId, at, false, null, assignee);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "version", 0);
        return r;
    }

    private void vehicles() {
        when(customerReader.findVehicleSummaries(anyLong(), anyCollection())).thenReturn(Map.of(
            4L, new VehicleSummary(4, "78라0004", 40, "최지은", "010-0000-0004", true),
            7L, new VehicleSummary(7, "22사0007", 70, "서민준", "010-0000-0007", true)));
    }

    // ---------- 15·04: 목록 ----------

    @Test
    @DisplayName("15 목록의 각 항목에 그 예약의 담당 정비사(이름·colorIndex)가 실린다, 담당 후보는 요청당 한 번만 읽는다 (N+1 없음)")
    void listItemsCarryAssignee() {
        candidates();
        vehicles();
        when(repository.findInPeriod(SHOP, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay())).thenReturn(List.of(
            reservation(1, 4, DAY.atTime(10, 0), MECHANIC), reservation(2, 7, DAY.atTime(14, 30), OWNER),
            reservation(3, 4, DAY.atTime(16, 0), MECHANIC)));

        List<ReservationListResponse.Item> items = service.list(SHOP, DAY, DAY).items();

        assertThat(items).extracting(ReservationListResponse.Item::assignee)
            .containsExactly(MECHANIC_VIEW, OWNER_VIEW, MECHANIC_VIEW);
        verify(staffReader, times(1)).findAssignees(SHOP);
    }

    @Test
    @DisplayName("예약이 없는 기간은 담당 후보를 읽지 않는다")
    void emptyListDoesNotReadStaff() {
        when(repository.findInPeriod(anyLong(), any(), any())).thenReturn(List.of());

        assertThat(service.list(SHOP, DAY, DAY).items()).isEmpty();

        verify(staffReader, never()).findAssignees(anyLong());
    }

    @Test
    @DisplayName("04 오늘 예약(하루치)도 같은 assignee를 싣는다: 15번 목록의 값을 그대로 옮긴다")
    void findByDateCarriesAssignee() {
        candidates();
        vehicles();
        when(repository.findInPeriod(SHOP, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay())).thenReturn(List.of(
            reservation(1, 4, DAY.atTime(10, 0), MECHANIC), reservation(2, 7, DAY.atTime(14, 30), OWNER)));

        ReservationDay day = service.findByDate(SHOP, DAY);

        assertThat(day.reservations()).extracting(r -> r.assignee()).containsExactly(MECHANIC_VIEW, OWNER_VIEW);
    }

    @Test
    @DisplayName("담당 정보가 후보에 없는 예약은 서버 버그로 드러낸다 (복합 FK로 같은 정비소 직원임이 보장되므로 정상 데이터에는 없다)")
    void missingAssigneeIsServerBug() {
        when(staffReader.findAssignees(SHOP)).thenReturn(List.of(new AssigneeRef(OWNER, "박사장", StaffRole.OWNER, 0)));
        vehicles();
        when(repository.findInPeriod(anyLong(), any(), any())).thenReturn(List.of(reservation(1, 4, DAY.atTime(10, 0), MECHANIC)));

        assertThatThrownBy(() -> service.list(SHOP, DAY, DAY)).isInstanceOf(IllegalStateException.class);
    }

    // ---------- 17: 상세 ----------

    @Test
    @DisplayName("17 상세(getOwnedDetail)에 담당 정비사가 실린다")
    void detailCarriesAssignee() {
        candidates();
        Reservation r = reservation(5, 4, DAY.atTime(15, 0), MECHANIC);
        when(repository.findByIdAndShopId(5L, SHOP)).thenReturn(Optional.of(r));

        ReservationDetail detail = service.getOwnedDetail(SHOP, 5L);

        assertThat(detail.assignee()).isEqualTo(MECHANIC_VIEW);
    }

    // ---------- 16·19: 등록·변경 응답 ----------

    @Test
    @DisplayName("16 등록 응답에 담당 정비사가 실리고, 담당 후보는 검증과 같은 한 번의 조회로 쓴다")
    void createResponseCarriesAssignee() {
        candidates();
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of());
        when(repository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 9L);
            ReflectionTestUtils.setField(saved, "version", 0);
            return saved;
        });

        ReservationCreateResponse response = service.create(SHOP, OWNER,
            new ReservationCreateRequest(4L, DAY.plusDays(3).atTime(10, 0), null, MECHANIC));

        assertThat(response.assignee()).isEqualTo(MECHANIC_VIEW);
        verify(staffReader, times(1)).findAssignees(SHOP);
    }

    @Test
    @DisplayName("19 변경 응답에 그 예약의 담당 정비사가 실린다 (재배정은 #37, 여기서는 지금 담당)")
    void updateResponseCarriesAssignee() {
        candidates();
        Reservation r = reservation(2, 4, DAY.plusDays(2).atTime(9, 0), MECHANIC);
        when(repository.findByIdAndShopId(2L, SHOP)).thenReturn(Optional.of(r));

        ReservationResponse response = service.update(ReservationTestStaff.owner(SHOP), 2L,
            new ReservationUpdateRequest(0, null, new ReservationCreateRequest.Purpose(null, null, "메모"), null, null));

        assertThat(response.assignee()).isEqualTo(MECHANIC_VIEW);
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("19 재배정(assigneeId) 응답의 assignee는 새 담당이다 (08 19번 재배정 예시: 김정비 → 박사장)")
    void reassignResponseCarriesNewAssignee() {
        candidates();
        Reservation r = reservation(2, 4, DAY.plusDays(2).atTime(9, 0), MECHANIC);
        when(repository.findByIdAndShopId(2L, SHOP)).thenReturn(Optional.of(r));

        ReservationResponse response = service.update(ReservationTestStaff.owner(SHOP), 2L,
            new ReservationUpdateRequest(0, null, null, OWNER, null));

        assertThat(response.assignee()).isEqualTo(OWNER_VIEW);
    }

    @Test
    @DisplayName("방문 완료·취소·노쇼(18·20·21) 응답에는 담당을 싣지 않는다 (08 예시에 없음) — 담당 후보도 읽지 않는다")
    void statusChangesDoNotCarryAssignee() {
        Reservation r = reservation(2, 4, DAY.plusDays(2).atTime(9, 0), MECHANIC);
        when(repository.findByIdAndShopId(2L, SHOP)).thenReturn(Optional.of(r));

        ReservationResponse completed = service.complete(SHOP, 2L, 0);

        assertThat(completed.assignee()).isNull();
        verify(staffReader, never()).findAssignees(anyLong());
    }
}
