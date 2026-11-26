package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationRevision;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.entity.RevisionKind;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRevisionRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.LoginStaff;
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
 * API 19의 담당 재배정 (08 19번, R24·R18·X13, D-53): 마감·역할·사유, 이력 저장, 입력 검증 순서. Repository는 가짜(DB 없음).
 * 날짜·역할 판정 자체는 {@code AssigneeChangePolicyTest}가 보고, 여기서는 service가 그 판정을 언제·무엇을 기준으로 부르는지 본다.
 * 이력이 실제 표에 저장되는 것과 정비소 격리는 {@code ReservationReassignDbTest}가 본다.
 */
class ReservationReassignServiceTest {

    private static final long SHOP = 3L;
    private static final long OWNER_ID = 7L;
    private static final long MECHANIC_ID = 8L;
    private static final long THIRD_ID = 9L;
    private static final long OTHER_SHOP_STAFF = 99L;
    private static final long VEHICLE = 4L;
    private static final long RES_ID = 5L;

    private static final LoginStaff OWNER = new LoginStaff(OWNER_ID, SHOP, StaffRole.OWNER);
    private static final LoginStaff MECHANIC = new LoginStaff(MECHANIC_ID, SHOP, StaffRole.MECHANIC);

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    private final ReservationRepository repository = mock(ReservationRepository.class);
    private final ReservationRevisionRepository revisions = mock(ReservationRevisionRepository.class);
    private final StaffReader staffReader = mock(StaffReader.class);
    private final ReservationService service = new ReservationService(repository, mock(ReservationItemRepository.class),
        revisions, mock(CustomerReader.class), mock(ItemReader.class), staffReader, clock);

    private Reservation existing(LocalDateTime at, ReservationStatus status) {
        Reservation r = Reservation.register(SHOP, VEHICLE, at, false, "기존 메모", OWNER_ID, OWNER_ID);
        ReflectionTestUtils.setField(r, "id", RES_ID);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", 0);
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.of(r));
        when(staffReader.findAssignees(SHOP)).thenReturn(List.of(
            new AssigneeRef(OWNER_ID, "박사장", StaffRole.OWNER, 0),
            new AssigneeRef(MECHANIC_ID, "김정비", StaffRole.MECHANIC, 1),
            new AssigneeRef(THIRD_ID, "이정비", StaffRole.MECHANIC, 2)));
        return r;
    }

    private Reservation tomorrow() {
        return existing(LocalDateTime.of(2026, 11, 25, 10, 0), ReservationStatus.RESERVED);
    }

    private Reservation today() {
        return existing(LocalDateTime.of(2026, 11, 24, 17, 0), ReservationStatus.RESERVED);
    }

    private static ReservationUpdateRequest reassign(long assigneeId, String reason) {
        return new ReservationUpdateRequest(0, null, null, assigneeId, reason);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    private static void assertInvalidInput(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(e.fieldErrors()).extracting("field").containsExactly(field);
        });
    }

    private void assertNothingSaved(Reservation r, long assigneeBefore) {
        verify(repository, never()).saveAndFlush(any());
        verify(revisions, never()).save(any());
        assertThat(r.getAssigneeStaffId()).isEqualTo(assigneeBefore);
    }

    @Test
    @DisplayName("R24(a) 전날까지는 정비사도 재배정 가능 — 담당이 바뀌고 이력에 이전·이후 담당·수정자가 남는다 (사유 없음 → null)")
    void reassignedBeforeTheDay() {
        Reservation r = tomorrow();

        ReservationResponse response = service.update(MECHANIC, RES_ID, reassign(THIRD_ID, null));

        assertThat(response.reservationId()).isEqualTo(RES_ID);
        assertThat(r.getAssigneeStaffId()).isEqualTo(THIRD_ID);
        verify(repository).saveAndFlush(r);
        ArgumentCaptor<ReservationRevision> saved = ArgumentCaptor.forClass(ReservationRevision.class);
        verify(revisions).save(saved.capture());
        ReservationRevision rev = saved.getValue();
        assertThat(rev.getKind()).isEqualTo(RevisionKind.ASSIGNEE);
        assertThat(rev.getShopId()).isEqualTo(SHOP);
        assertThat(rev.getReservationId()).isEqualTo(RES_ID);
        assertThat(rev.getChangedByStaffId()).isEqualTo(MECHANIC_ID);
        assertThat(rev.getPrevAssigneeId()).isEqualTo(OWNER_ID);
        assertThat(rev.getNewAssigneeId()).isEqualTo(THIRD_ID);
        assertThat(rev.getReason()).isNull();
    }

    @Test
    @DisplayName("R24(c) 당일 사장 + 사유 → 성공, 이력에 사유가 남는다")
    void sameDayOwnerWithReason() {
        Reservation r = today();

        service.update(OWNER, RES_ID, reassign(MECHANIC_ID, "정비사 휴가"));

        assertThat(r.getAssigneeStaffId()).isEqualTo(MECHANIC_ID);
        ArgumentCaptor<ReservationRevision> saved = ArgumentCaptor.forClass(ReservationRevision.class);
        verify(revisions).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("정비사 휴가");
        assertThat(saved.getValue().getChangedByStaffId()).isEqualTo(OWNER_ID);
    }

    @Test
    @DisplayName("R24(d) 당일 사장이 사유 없이 → 400 INVALID_INPUT(reassignReason), 담당·이력 그대로")
    void sameDayOwnerWithoutReason() {
        Reservation r = today();

        assertInvalidInput(() -> service.update(OWNER, RES_ID, reassign(MECHANIC_ID, null)), "reassignReason");
        assertInvalidInput(() -> service.update(OWNER, RES_ID, reassign(MECHANIC_ID, "  ")), "reassignReason");

        assertNothingSaved(r, OWNER_ID);
    }

    @Test
    @DisplayName("R24(b) 당일 정비사 → 409 ASSIGNEE_CHANGE_CLOSED, 담당·이력 그대로")
    void sameDayMechanic() {
        Reservation r = today();

        assertCode(() -> service.update(MECHANIC, RES_ID, reassign(THIRD_ID, "사유")), ErrorCode.ASSIGNEE_CHANGE_CLOSED);

        assertNothingSaved(r, OWNER_ID);
    }

    @Test
    @DisplayName("R24(f) 지난 날짜는 사장도 409 ASSIGNEE_CHANGE_CLOSED")
    void pastDateOwner() {
        Reservation r = existing(LocalDateTime.of(2026, 11, 23, 10, 0), ReservationStatus.RESERVED);

        assertCode(() -> service.update(OWNER, RES_ID, reassign(MECHANIC_ID, "사유")), ErrorCode.ASSIGNEE_CHANGE_CLOSED);

        assertNothingSaved(r, OWNER_ID);
    }

    @Test
    @DisplayName("R24(e) 진행중 예약은 사장도 409 INVALID_STATUS_TRANSITION — 마감 판정보다 상태 확인이 먼저")
    void inProgressOwner() {
        Reservation r = existing(LocalDateTime.of(2026, 11, 24, 14, 0), ReservationStatus.IN_PROGRESS);

        assertCode(() -> service.update(OWNER, RES_ID, reassign(MECHANIC_ID, "사유")), ErrorCode.INVALID_STATUS_TRANSITION);

        assertNothingSaved(r, OWNER_ID);
    }

    @Test
    @DisplayName("마감 기준은 변경 전 예약 날짜 — 내일 예약을 오늘로 옮기며 재배정해도 정비사 가능 / 오늘 예약을 다음 주로 옮기며 재배정은 정비사 불가")
    void deadlineUsesTheDateBeforeTheChange() {
        Reservation tomorrow = tomorrow();
        service.update(MECHANIC, RES_ID, new ReservationUpdateRequest(0, LocalDateTime.of(2026, 11, 24, 18, 0), null, THIRD_ID, null));
        assertThat(tomorrow.getAssigneeStaffId()).isEqualTo(THIRD_ID);
        assertThat(tomorrow.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 18, 0));

        Reservation today = today();
        assertCode(() -> service.update(MECHANIC, RES_ID,
            new ReservationUpdateRequest(0, LocalDateTime.of(2026, 12, 1, 10, 0), null, THIRD_ID, null)),
            ErrorCode.ASSIGNEE_CHANGE_CLOSED);
        assertThat(today.getAssigneeStaffId()).isEqualTo(OWNER_ID);
        assertThat(today.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 17, 0));
    }

    @Test
    @DisplayName("지금 담당과 같은 assigneeId만 보내면 바뀐 게 없어 400, 마감 판정·이력 없음 (지난 날짜여도 마감 오류가 아님)")
    void sameAssigneeOnlyIsNothingToChange() {
        Reservation r = existing(LocalDateTime.of(2026, 11, 23, 10, 0), ReservationStatus.RESERVED);

        assertInvalidInput(() -> service.update(MECHANIC, RES_ID, reassign(OWNER_ID, null)), "assigneeId");

        assertNothingSaved(r, OWNER_ID);
    }

    @Test
    @DisplayName("지금 담당과 같은 assigneeId에 메모 변경을 함께 보내면 메모만 바뀌고 이력은 남기지 않는다 (당일 정비사도 가능)")
    void sameAssigneeWithMemoChange() {
        Reservation r = today();
        ReservationUpdateRequest request = new ReservationUpdateRequest(0, null,
            new ReservationCreateRequest.Purpose(null, null, "새 메모"), OWNER_ID, null);

        service.update(MECHANIC, RES_ID, request);

        assertThat(r.getPurposeMemo()).isEqualTo("새 메모");
        assertThat(r.getAssigneeStaffId()).isEqualTo(OWNER_ID);
        verify(repository).saveAndFlush(r);
        verify(revisions, never()).save(any());
    }

    @Test
    @DisplayName("X13 이 정비소 직원이 아닌 assigneeId → 400 INVALID_INPUT(assigneeId), 마감·충돌 판정보다 먼저, 저장 없음")
    void assigneeOfAnotherShop() {
        Reservation r = today(); // 당일 정비사라면 마감(409)이지만 입력 검증(400)이 먼저다

        assertInvalidInput(() -> service.update(MECHANIC, RES_ID, reassign(OTHER_SHOP_STAFF, "사유")), "assigneeId");

        assertNothingSaved(r, OWNER_ID);
        verify(repository, never()).findSlotConflicts(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("R18 재배정 대상 담당이 같은 시각에 이미 예약이 있으면 409 MECHANIC_SLOT_CONFLICT + 그 예약 번호, 담당·이력 그대로")
    void newAssigneeIsBusyAtThatTime() {
        Reservation r = tomorrow();
        Reservation busy = Reservation.register(SHOP, 6L, r.getReservedAt(), false, null, MECHANIC_ID, OWNER_ID);
        ReflectionTestUtils.setField(busy, "id", 77L);
        when(repository.findSlotConflicts(eq(SHOP), eq(MECHANIC_ID), eq(r.getReservedAt()), any())).thenReturn(List.of(busy));

        assertThatThrownBy(() -> service.update(OWNER, RES_ID, reassign(MECHANIC_ID, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.MECHANIC_SLOT_CONFLICT);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", 77L));
            });

        assertNothingSaved(r, OWNER_ID);
    }

    @Test
    @DisplayName("재배정과 일시 변경을 함께 보내면 담당 중복은 새 담당·새 시각으로 본다")
    void conflictIsCheckedWithNewAssigneeAndNewTime() {
        tomorrow();
        LocalDateTime newTime = LocalDateTime.of(2026, 11, 26, 9, 30);

        service.update(OWNER, RES_ID, new ReservationUpdateRequest(0, newTime, null, THIRD_ID, null));

        verify(repository).findSlotConflicts(eq(SHOP), eq(THIRD_ID), eq(newTime), any());
    }

    @Test
    @DisplayName("담당을 바꾸지 않는 변경(메모만)은 이력을 남기지 않고 담당 중복도 보지 않는다")
    void noRevisionWithoutReassign() {
        Reservation r = tomorrow();

        service.update(OWNER, RES_ID, new ReservationUpdateRequest(0, null,
            new ReservationCreateRequest.Purpose(null, null, "메모만"), null, null));

        verify(repository).saveAndFlush(r);
        verify(revisions, never()).save(any());
        verify(repository, never()).findSlotConflicts(anyLong(), anyLong(), any(), any());
    }
}
