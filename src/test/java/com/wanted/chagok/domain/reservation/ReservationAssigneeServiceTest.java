package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.AssigneeRef;
import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.StaffRole;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 예약 등록·변경의 담당 정비사 규칙 (08 16·19번, 04 F-RSV-01, R15~R20, X13): 담당 검증(없음·남의 것은 400), 같은 담당·같은 시작 시각의
 * 유효 예약 중복(409 {@code MECHANIC_SLOT_CONFLICT} + 겹친 예약 번호), 같은 시각 다른 담당 허용, 변경(19)의 자기 자신 제외.
 * Repository·다른 도메인 계약은 가짜(DB 없음). 실제 SQL은 {@code ReservationAssigneeDbTest}.
 */
class ReservationAssigneeServiceTest {

    private static final long SHOP = 3L;
    private static final long OWNER = 7L;
    private static final long MECHANIC = 8L;
    private static final long OTHER_SHOP_STAFF = 99L;
    private static final long VEHICLE = 4L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 27, 11, 30);

    private final ReservationRepository repository = mock(ReservationRepository.class);
    private final ReservationItemRepository itemRepository = mock(ReservationItemRepository.class);
    private final CustomerReader customerReader = mock(CustomerReader.class);
    private final ItemReader itemReader = mock(ItemReader.class);
    private final StaffReader staffReader = mock(StaffReader.class);
    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);
    private final ReservationService service =
        new ReservationService(repository, itemRepository, customerReader, itemReader, staffReader, clock);

    private void thisShopStaff() {
        when(staffReader.findAssignees(SHOP)).thenReturn(List.of(
            new AssigneeRef(OWNER, "박사장", StaffRole.OWNER, 0), new AssigneeRef(MECHANIC, "김정비", StaffRole.MECHANIC, 1)));
    }

    private void savedAs(long id) {
        when(repository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", id);
            ReflectionTestUtils.setField(r, "version", 0);
            return r;
        });
    }

    private static ReservationCreateRequest request(Long assigneeId) {
        return new ReservationCreateRequest(VEHICLE, AT, null, assigneeId);
    }

    private static Reservation existing(long id, long assignee, LocalDateTime at, ReservationStatus status) {
        Reservation r = Reservation.register(SHOP, 20L, at, false, null, assignee);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", 0);
        return r;
    }

    // ---------- 담당 검증 (R19·R20·X13) ----------

    @Test
    @DisplayName("R19 assigneeId가 없으면(null) 400 INVALID_INPUT(assigneeId), 저장하지 않는다")
    void missingAssignee() {
        assertThatThrownBy(() -> service.create(SHOP, OWNER, request(null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting("field").containsExactly("assigneeId");
            });
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("R20·X13 다른 정비소 직원·없는 id를 담당으로 보내면 400 INVALID_INPUT(assigneeId), 정비소 후보는 토큰의 shopId로만 가져온다")
    void assigneeMustBeOfThisShop() {
        thisShopStaff();

        for (long bad : new long[] {OTHER_SHOP_STAFF, -1L, 0L}) {
            assertThatThrownBy(() -> service.create(SHOP, OWNER, request(bad)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.fieldErrors()).extracting("field").containsExactly("assigneeId");
                });
        }
        verify(staffReader, org.mockito.Mockito.times(3)).findAssignees(SHOP);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("담당 검증은 08 16번 순서: 항목 검증 다음, 날짜 검증 앞 (잘못된 담당 + 지난 날짜면 담당 오류가 먼저)")
    void assigneeCheckedBeforeDate() {
        thisShopStaff();
        LocalDateTime yesterday = LocalDateTime.of(2026, 11, 23, 10, 0);

        assertThatThrownBy(() -> service.create(SHOP, OWNER, new ReservationCreateRequest(VEHICLE, yesterday, null, OTHER_SHOP_STAFF)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("담당은 요청의 assigneeId이고 등록자는 로그인 계정이다: 사장이 정비사를 담당으로 등록하면 담당 8, 등록자 7")
    void assigneeAndCreatorAreSeparate() {
        thisShopStaff();
        savedAs(10L);
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of());
        when(repository.findSlotConflicts(eq(SHOP), eq(MECHANIC), eq(AT), anyCollection())).thenReturn(List.of());

        service.create(SHOP, OWNER, request(MECHANIC));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getAssigneeStaffId()).isEqualTo(MECHANIC);
        assertThat(saved.getValue().getCreatedByStaffId()).isEqualTo(OWNER);
    }

    // ---------- 담당 중복 (R15·R16·R17) ----------

    @Test
    @DisplayName("R15 같은 담당·같은 시작 시각에 유효 예약이 있으면 409 MECHANIC_SLOT_CONFLICT + 겹친 예약 번호, 저장하지 않는다")
    void slotConflictOnCreate() {
        thisShopStaff();
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of());
        when(repository.findSlotConflicts(eq(SHOP), eq(MECHANIC), eq(AT), anyCollection()))
            .thenReturn(List.of(existing(1L, MECHANIC, AT, ReservationStatus.RESERVED)));

        assertThatThrownBy(() -> service.create(SHOP, OWNER, request(MECHANIC)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.MECHANIC_SLOT_CONFLICT);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", 1L));
            });
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("충돌 조회는 예약·진행중만 막는다 (R17: 취소·완료·노쇼는 막지 않음), 시작 시각이 같은 것만")
    void conflictLookupUsesOnlyReservedAndInProgress() {
        thisShopStaff();
        savedAs(10L);
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of());
        when(repository.findSlotConflicts(anyLong(), anyLong(), any(), anyCollection())).thenReturn(List.of());

        service.create(SHOP, OWNER, request(OWNER));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ReservationStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repository).findSlotConflicts(eq(SHOP), eq(OWNER), eq(AT), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(ReservationStatus.RESERVED, ReservationStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("R16 같은 시각이어도 담당이 다르면 충돌이 아니다 (다른 담당 조회에는 겹침이 없다) → 저장")
    void sameTimeOtherAssigneeIsAllowed() {
        thisShopStaff();
        savedAs(10L);
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of());
        // 사장(7)의 같은 시각 예약은 있지만, 요청은 정비사(8)라 정비사 조회에는 없다
        when(repository.findSlotConflicts(eq(SHOP), eq(MECHANIC), eq(AT), anyCollection())).thenReturn(List.of());

        service.create(SHOP, OWNER, request(MECHANIC));

        verify(repository).save(any(Reservation.class));
    }

    @Test
    @DisplayName("같은 차량 유효 예약(409 RESERVATION_ACTIVE_EXISTS)이 담당 중복보다 먼저다 (08 16번 순서)")
    void activeVehicleChecked_beforeSlotConflict() {
        thisShopStaff();
        Reservation sameVehicle = existing(5L, OWNER, LocalDateTime.of(2026, 11, 30, 10, 0), ReservationStatus.RESERVED);
        ReflectionTestUtils.setField(sameVehicle, "vehicleId", VEHICLE);
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of(sameVehicle));

        assertThatThrownBy(() -> service.create(SHOP, OWNER, request(MECHANIC)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RESERVATION_ACTIVE_EXISTS));
        verify(repository, never()).findSlotConflicts(anyLong(), anyLong(), any(), anyCollection());
    }

    // ---------- 변경 (R18) ----------

    private Reservation stubOwned(long id, long assignee, LocalDateTime at) {
        Reservation current = existing(id, assignee, at, ReservationStatus.RESERVED);
        ReflectionTestUtils.setField(current, "vehicleId", 20L);
        when(repository.findByIdAndShopId(id, SHOP)).thenReturn(Optional.of(current));
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of(current));
        return current;
    }

    @Test
    @DisplayName("R18 일시를 바꿔 같은 담당·같은 시각이 되면 409 MECHANIC_SLOT_CONFLICT + 겹친 예약 번호, 예약은 그대로(저장 안 함)")
    void updateSlotConflict() {
        stubOwned(2L, MECHANIC, LocalDateTime.of(2026, 11, 26, 9, 0));
        when(repository.findSlotConflicts(eq(SHOP), eq(MECHANIC), eq(AT), anyCollection()))
            .thenReturn(List.of(existing(1L, MECHANIC, AT, ReservationStatus.IN_PROGRESS)));

        assertThatThrownBy(() -> service.update(SHOP, 2L, new ReservationUpdateRequest(0, AT, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.MECHANIC_SLOT_CONFLICT);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", 1L));
            });
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("변경의 중복 확인은 자기 자신을 뺀다: 같은 시각으로 다시 보내도 자기 예약과는 충돌하지 않는다")
    void updateExcludesItself() {
        Reservation current = stubOwned(2L, MECHANIC, AT);
        when(repository.findSlotConflicts(eq(SHOP), eq(MECHANIC), eq(AT), anyCollection())).thenReturn(List.of(current));

        service.update(SHOP, 2L, new ReservationUpdateRequest(0, AT, null));

        verify(repository).saveAndFlush(current);
    }

    @Test
    @DisplayName("변경 때 중복 확인은 예약의 지금 담당으로 한다 (요청에 assigneeId가 와도 재배정은 #37, 여기서는 읽지 않는다)")
    void updateChecksCurrentAssignee() {
        stubOwned(2L, MECHANIC, LocalDateTime.of(2026, 11, 26, 9, 0));
        when(repository.findSlotConflicts(anyLong(), anyLong(), any(), anyCollection())).thenReturn(List.of());

        service.update(SHOP, 2L, new ReservationUpdateRequest(0, AT, null));

        verify(repository).findSlotConflicts(eq(SHOP), eq(MECHANIC), eq(AT), anyCollection());
    }

    @Test
    @DisplayName("메모·항목만 바꿀 때는 시각이 그대로라 담당 중복을 확인하지 않는다")
    void updateWithoutTimeSkipsSlotCheck() {
        stubOwned(2L, MECHANIC, AT);

        service.update(SHOP, 2L, new ReservationUpdateRequest(0, null, new ReservationCreateRequest.Purpose(null, null, "메모")));

        verify(repository, never()).findSlotConflicts(anyLong(), anyLong(), any(), anyCollection());
    }
}
