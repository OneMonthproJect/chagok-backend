package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.reservation.dto.ReservationCorrectRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
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
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * API 40 지난 예약 정정 (08 7.1 40번, R25~R29, X14, D-53): 확인 순서, 허용표, 권한·기간, 두 종류 이력 저장. Repository는 가짜(DB 없음).
 * 허용표·권한 판정 자체는 {@code ReservationCorrectionTest}·{@code ReservationCorrectionPolicyTest}가 보고, 여기서는 service가
 * 그 판정을 어떤 순서로 부르고 무엇을 이력에 남기는지 본다. 실제 표 저장·정비소 격리는 {@code ReservationCorrectionDbTest}가 본다.
 */
class ReservationCorrectServiceTest {

    private static final long SHOP = 3L;
    private static final long OWNER_ID = 7L;
    private static final long ASSIGNEE_ID = 8L;
    private static final long OTHER_MECHANIC_ID = 9L;
    private static final long RES_ID = 5L;

    private static final LoginStaff OWNER = new LoginStaff(OWNER_ID, SHOP, StaffRole.OWNER);
    private static final LoginStaff ASSIGNEE = new LoginStaff(ASSIGNEE_ID, SHOP, StaffRole.MECHANIC);
    private static final LoginStaff OTHER_MECHANIC = new LoginStaff(OTHER_MECHANIC_ID, SHOP, StaffRole.MECHANIC);

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    private final ReservationRepository repository = mock(ReservationRepository.class);
    private final ReservationRevisionRepository revisions = mock(ReservationRevisionRepository.class);
    private final ReservationService service = new ReservationService(repository, mock(ReservationItemRepository.class),
        revisions, mock(CustomerReader.class), mock(CustomerWriter.class), mock(ItemReader.class),
        mock(StaffReader.class), clock);

    private Reservation existing(ReservationStatus status, LocalDateTime at, String memo) {
        Reservation r = Reservation.register(SHOP, 4L, at, false, memo, ASSIGNEE_ID, OWNER_ID);
        ReflectionTestUtils.setField(r, "id", RES_ID);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", 1);
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.of(r));
        return r;
    }

    /** 예약 날짜가 4일 전(11-20)인 끝난 예약 — 담당 정비사의 메모 정정 기간(+3일)이 이미 지났다. */
    private Reservation old(ReservationStatus status, String memo) {
        return existing(status, LocalDateTime.of(2026, 11, 20, 10, 0), memo);
    }

    private static ReservationCorrectRequest status(ReservationStatus to, String reason) {
        return new ReservationCorrectRequest(1, to, null, reason);
    }

    private static ReservationCorrectRequest memo(String memo, String reason) {
        return new ReservationCorrectRequest(1, null, memo, reason);
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

    private void assertNothingSaved() {
        verify(repository, never()).saveAndFlush(any());
        verify(revisions, never()).save(any());
    }

    @Test
    @DisplayName("R25 사장이 노쇼를 취소로 정정 → 상태가 바뀌고 응답은 18·20·21 모양, 이력 STATUS 한 행(이전·이후 상태·사유·수정자)")
    void ownerCorrectsStatus() {
        Reservation r = old(ReservationStatus.NO_SHOW, "메모");

        ReservationResponse response = service.correct(OWNER, RES_ID, status(ReservationStatus.CANCELED, "고객 전화"));

        assertThat(response.reservationId()).isEqualTo(RES_ID);
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(response.reservedAt()).isNull();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(r.getPurposeMemo()).isEqualTo("메모");
        verify(repository).saveAndFlush(r);
        ArgumentCaptor<ReservationRevision> saved = ArgumentCaptor.forClass(ReservationRevision.class);
        verify(revisions).save(saved.capture());
        ReservationRevision rev = saved.getValue();
        assertThat(rev.getKind()).isEqualTo(RevisionKind.STATUS);
        assertThat(rev.getShopId()).isEqualTo(SHOP);
        assertThat(rev.getReservationId()).isEqualTo(RES_ID);
        assertThat(rev.getChangedByStaffId()).isEqualTo(OWNER_ID);
        assertThat(rev.getPrevStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(rev.getNewStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(rev.getReason()).isEqualTo("고객 전화");
        assertThat(rev.getPrevMemo()).isNull();
        assertThat(rev.getPrevAssigneeId()).isNull();
    }

    @Test
    @DisplayName("R26 완료→취소·노쇼 정정은 사장도 409 INVALID_STATUS_TRANSITION, 상태·이력 그대로")
    void completedCannotBeCorrectedToCancel() {
        Reservation r = old(ReservationStatus.COMPLETED, null);

        assertCode(() -> service.correct(OWNER, RES_ID, status(ReservationStatus.CANCELED, null)), ErrorCode.INVALID_STATUS_TRANSITION);
        assertCode(() -> service.correct(OWNER, RES_ID, status(ReservationStatus.NO_SHOW, null)), ErrorCode.INVALID_STATUS_TRANSITION);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertNothingSaved();
    }

    @Test
    @DisplayName("R27 정비사의 상태 정정은 403 FORBIDDEN (담당 정비사여도), 상태·이력 그대로")
    void mechanicCannotCorrectStatus() {
        Reservation r = old(ReservationStatus.NO_SHOW, null);

        assertCode(() -> service.correct(ASSIGNEE, RES_ID, status(ReservationStatus.COMPLETED, null)), ErrorCode.FORBIDDEN);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertNothingSaved();
    }

    @Test
    @DisplayName("R28 담당 정비사의 메모 정정: 예약 날짜 + 3일째는 성공(이력 MEMO), 4일째는 403 EDIT_PERIOD_EXPIRED, 사장은 4일째도 성공")
    void memoPeriodForTheAssignee() {
        // 오늘 11-24: 예약 11-21(3일 전)은 성공, 11-20(4일 전)은 기간 초과
        Reservation within = existing(ReservationStatus.COMPLETED, LocalDateTime.of(2026, 11, 21, 10, 0), "기존");
        service.correct(ASSIGNEE, RES_ID, memo("고친 메모", "통화 반영"));
        assertThat(within.getPurposeMemo()).isEqualTo("고친 메모");

        Reservation expired = old(ReservationStatus.COMPLETED, "기존");
        assertCode(() -> service.correct(ASSIGNEE, RES_ID, memo("또 고침", null)), ErrorCode.EDIT_PERIOD_EXPIRED);
        assertThat(expired.getPurposeMemo()).isEqualTo("기존");

        service.correct(OWNER, RES_ID, memo("사장이 고침", null));
        assertThat(expired.getPurposeMemo()).isEqualTo("사장이 고침");
    }

    @Test
    @DisplayName("R29 담당이 아닌 정비사의 메모 정정은 403 FORBIDDEN, 이력·메모 그대로")
    void notTheAssignee() {
        Reservation r = existing(ReservationStatus.COMPLETED, LocalDateTime.of(2026, 11, 24, 9, 0), "기존");

        assertCode(() -> service.correct(OTHER_MECHANIC, RES_ID, memo("고침", null)), ErrorCode.FORBIDDEN);

        assertThat(r.getPurposeMemo()).isEqualTo("기존");
        assertNothingSaved();
    }

    @Test
    @DisplayName("메모 정정 이력: 이전·이후 메모(없던 메모는 null), 빈 문자열로 비우면 이후 메모가 null, 사유가 공백이면 null")
    void memoRevisionValues() {
        old(ReservationStatus.CANCELED, null);
        service.correct(OWNER, RES_ID, memo("처음 메모", "   "));
        ArgumentCaptor<ReservationRevision> first = ArgumentCaptor.forClass(ReservationRevision.class);
        verify(revisions).save(first.capture());
        assertThat(first.getValue().getKind()).isEqualTo(RevisionKind.MEMO);
        assertThat(first.getValue().getPrevMemo()).isNull();
        assertThat(first.getValue().getNewMemo()).isEqualTo("처음 메모");
        assertThat(first.getValue().getReason()).isNull();

        Reservation r = old(ReservationStatus.CANCELED, "지울 메모");
        service.correct(OWNER, RES_ID, memo("", null));
        assertThat(r.getPurposeMemo()).isNull();
        ArgumentCaptor<ReservationRevision> second = ArgumentCaptor.forClass(ReservationRevision.class);
        verify(revisions, times(2)).save(second.capture());
        assertThat(second.getValue().getPrevMemo()).isEqualTo("지울 메모");
        assertThat(second.getValue().getNewMemo()).isNull();
    }

    @Test
    @DisplayName("상태와 메모를 함께 고치면 STATUS·MEMO 두 행을 같은 사유·수정자로 남긴다")
    void statusAndMemoTogether() {
        Reservation r = old(ReservationStatus.NO_SHOW, "기존");

        service.correct(OWNER, RES_ID, new ReservationCorrectRequest(1, ReservationStatus.COMPLETED, "새 메모", "함께 정정"));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(r.getPurposeMemo()).isEqualTo("새 메모");
        ArgumentCaptor<ReservationRevision> saved = ArgumentCaptor.forClass(ReservationRevision.class);
        verify(revisions, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ReservationRevision::getKind)
            .containsExactly(RevisionKind.STATUS, RevisionKind.MEMO);
        assertThat(saved.getAllValues()).extracting(ReservationRevision::getReason).containsOnly("함께 정정");
        assertThat(saved.getAllValues()).extracting(ReservationRevision::getChangedByStaffId).containsOnly(OWNER_ID);
    }

    @Test
    @DisplayName("함께 보낸 상태가 허용표 밖이면 메모도 바뀌지 않는다 (둘 다 허용될 때만 적용)")
    void forbiddenStatusKeepsTheMemo() {
        Reservation r = old(ReservationStatus.COMPLETED, "기존");

        assertCode(() -> service.correct(OWNER, RES_ID,
            new ReservationCorrectRequest(1, ReservationStatus.CANCELED, "새 메모", null)), ErrorCode.INVALID_STATUS_TRANSITION);

        assertThat(r.getPurposeMemo()).isEqualTo("기존");
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertNothingSaved();
    }

    @Test
    @DisplayName("정비사가 메모만 고치려고 status를 함께 보내면 status 때문에 403, 같은 값을 보내도 마찬가지")
    void mechanicSendingStatusIsForbidden() {
        existing(ReservationStatus.NO_SHOW, LocalDateTime.of(2026, 11, 24, 9, 0), "기존");

        assertCode(() -> service.correct(ASSIGNEE, RES_ID,
            new ReservationCorrectRequest(1, ReservationStatus.NO_SHOW, "새 메모", null)), ErrorCode.FORBIDDEN);

        assertNothingSaved();
    }

    @Test
    @DisplayName("지금 값과 같은 필드는 바꾸는 것이 아니다 — 같은 상태만이면 400(status), 같은 메모만이면 400(purposeMemo), 이력 없음")
    void unchangedFieldsAreNotChanges() {
        Reservation r = old(ReservationStatus.CANCELED, "기존");

        assertInvalidInput(() -> service.correct(OWNER, RES_ID, status(ReservationStatus.CANCELED, null)), "status");
        assertInvalidInput(() -> service.correct(OWNER, RES_ID, memo("기존", null)), "purposeMemo");
        assertInvalidInput(() -> service.correct(OWNER, RES_ID, new ReservationCorrectRequest(1, ReservationStatus.CANCELED, "기존", null)), "status");

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertNothingSaved();
    }

    @Test
    @DisplayName("같은 상태에 다른 메모를 보내면 상태는 건너뛰고 메모만 정정하며 이력은 MEMO 한 행")
    void sameStatusWithNewMemo() {
        Reservation r = old(ReservationStatus.CANCELED, "기존");

        service.correct(OWNER, RES_ID, new ReservationCorrectRequest(1, ReservationStatus.CANCELED, "새 메모", null));

        assertThat(r.getPurposeMemo()).isEqualTo("새 메모");
        ArgumentCaptor<ReservationRevision> saved = ArgumentCaptor.forClass(ReservationRevision.class);
        verify(revisions).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(RevisionKind.MEMO);
    }

    @Test
    @DisplayName("형식 오류(정정할 수 없는 상태 값: 예약·진행중)는 소유 확인보다 먼저 400, 조회 없음")
    void formatErrorsComeFirst() {
        assertInvalidInput(() -> service.correct(OWNER, RES_ID, status(ReservationStatus.RESERVED, null)), "status");
        assertInvalidInput(() -> service.correct(OWNER, RES_ID, status(ReservationStatus.IN_PROGRESS, null)), "status");

        verify(repository, never()).findByIdAndShopId(any(), any());
        assertNothingSaved();
    }

    @Test
    @DisplayName("status·purposeMemo를 둘 다 안 보낸 요청은 \"바뀐 것 없음\"이라 소유(404)·끝난 예약(409)·version(409) 확인 뒤에야 400(purposeMemo) — 08 40번 순서")
    void nothingSentIsCheckedAfterTheOtherChecks() {
        ReservationCorrectRequest nothing = new ReservationCorrectRequest(1, null, null, "사유만");

        // 남의 정비소·없는 예약이면 404 (X14)
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.empty());
        assertCode(() -> service.correct(OWNER, RES_ID, nothing), ErrorCode.NOT_FOUND);

        // 끝난 예약이 아니면 409, 낡은 version이면 409
        existing(ReservationStatus.RESERVED, LocalDateTime.of(2026, 11, 20, 10, 0), null);
        assertCode(() -> service.correct(OWNER, RES_ID, nothing), ErrorCode.INVALID_STATUS_TRANSITION);
        old(ReservationStatus.NO_SHOW, null);
        assertCode(() -> service.correct(OWNER, RES_ID, new ReservationCorrectRequest(0, null, null, null)),
            ErrorCode.CONCURRENT_MODIFICATION);

        // 앞의 확인을 모두 지나면 그제야 400
        assertInvalidInput(() -> service.correct(OWNER, RES_ID, nothing), "purposeMemo");
        assertNothingSaved();
    }

    @Test
    @DisplayName("X14 다른 정비소·없는 예약 → 404 NOT_FOUND, 저장 없음")
    void notOwned() {
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.empty());

        assertCode(() -> service.correct(OWNER, RES_ID, status(ReservationStatus.CANCELED, null)), ErrorCode.NOT_FOUND);

        assertNothingSaved();
    }

    @Test
    @DisplayName("끝난 예약이 아니면(예약·진행중) 409 INVALID_STATUS_TRANSITION이 version·권한보다 먼저")
    void unfinishedReservationIsRejectedFirst() {
        existing(ReservationStatus.RESERVED, LocalDateTime.of(2026, 11, 20, 10, 0), null);

        // 정비사의 상태 정정(403)이지만 정정 대상이 아닌 예약이라 상태 오류가 먼저다
        assertCode(() -> service.correct(ASSIGNEE, RES_ID,
            new ReservationCorrectRequest(0, ReservationStatus.CANCELED, null, null)), ErrorCode.INVALID_STATUS_TRANSITION);

        assertNothingSaved();
    }

    @Test
    @DisplayName("R12 낡은 version → 409 CONCURRENT_MODIFICATION이 권한(403)보다 먼저, 아무것도 바뀌지 않음")
    void staleVersionBeforePermission() {
        Reservation r = old(ReservationStatus.NO_SHOW, null);

        assertCode(() -> service.correct(ASSIGNEE, RES_ID,
            new ReservationCorrectRequest(0, ReservationStatus.CANCELED, null, null)), ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertNothingSaved();
    }
}
