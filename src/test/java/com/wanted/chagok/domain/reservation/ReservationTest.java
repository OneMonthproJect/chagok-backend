package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code Reservation} 상태 전이 단위 테스트 (DB 없음). R5·R10·R12 중 Entity가 정하는 부분.
 * {@code @Version}의 실제 증가와 저장 시 충돌은 DB가 있어야 확인되는데 그 DB 테스트는 아직 없다(이 PR에서는 미검증).
 */
class ReservationTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 27, 11, 30);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 15, 0);

    /** DB에서 읽어 온 것처럼 version·상태를 맞춘 예약 (저장 전에는 version이 null). */
    private static Reservation loaded(ReservationStatus status, int version) {
        Reservation r = Reservation.register(1L, 10L, AT, false, null, 7L);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", version);
        return r;
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    @DisplayName("새 예약: 상태 RESERVED, 담당·등록자는 등록한 계정 (D-51)")
    void register() {
        Reservation r = Reservation.register(1L, 10L, AT, true, "메모", 7L);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(r.getShopId()).isEqualTo(1L);
        assertThat(r.getVehicleId()).isEqualTo(10L);
        assertThat(r.getReservedAt()).isEqualTo(AT);
        assertThat(r.isPurposeOther()).isTrue();
        assertThat(r.getPurposeMemo()).isEqualTo("메모");
        assertThat(r.getAssigneeStaffId()).isEqualTo(7L);
        assertThat(r.getCreatedByStaffId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("R13 맞는 version으로 방문 완료 → COMPLETED (시각 전·후 구분 없음)")
    void complete() {
        Reservation r = loaded(ReservationStatus.RESERVED, 0);

        r.complete(0);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
    }

    @Test
    @DisplayName("R5 취소된 예약을 방문 완료 → 409 INVALID_STATUS_TRANSITION, 상태 그대로")
    void completeCanceled() {
        Reservation r = loaded(ReservationStatus.CANCELED, 0);

        assertCode(() -> r.complete(0), ErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 완료된 예약을 다시 완료 → 409 INVALID_STATUS_TRANSITION")
    void completeTwice() {
        Reservation r = loaded(ReservationStatus.COMPLETED, 1);

        assertCode(() -> r.complete(1), ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("R10 다른 직원이 이미 끝냈고 낡은 version → 상태 오류가 먼저 (CONCURRENT_MODIFICATION 아님)")
    void statusCheckComesBeforeVersionCheck() {
        Reservation r = loaded(ReservationStatus.COMPLETED, 1);

        assertCode(() -> r.complete(0), ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("R12 다른 직원이 일시를 바꿔 version이 올랐다(아직 RESERVED) → 409 CONCURRENT_MODIFICATION, 상태 그대로")
    void staleVersion() {
        Reservation r = loaded(ReservationStatus.RESERVED, 1);

        assertCode(() -> r.complete(0), ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("요청 version이 DB보다 크거나 음수여도 같은 409 (서버 값과 다르면 모두 충돌)")
    void futureOrNegativeVersion() {
        Reservation r = loaded(ReservationStatus.RESERVED, 0);

        assertCode(() -> r.complete(5), ErrorCode.CONCURRENT_MODIFICATION);
        assertCode(() -> r.complete(-1), ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    @DisplayName("처리 필요(D-23): RESERVED이고 예약 시각이 현재보다 이전일 때만 true")
    void needsAction() {
        // 예약은 11-27 11:30
        assertThat(loaded(ReservationStatus.RESERVED, 0).needsAction(LocalDateTime.of(2026, 11, 27, 11, 31))).isTrue();
        assertThat(loaded(ReservationStatus.RESERVED, 0).needsAction(LocalDateTime.of(2026, 11, 27, 11, 30))).isFalse(); // 정각은 아직
        assertThat(loaded(ReservationStatus.RESERVED, 0).needsAction(LocalDateTime.of(2026, 11, 27, 9, 0))).isFalse();
        assertThat(loaded(ReservationStatus.COMPLETED, 1).needsAction(LocalDateTime.of(2026, 11, 28, 9, 0))).isFalse();
        assertThat(loaded(ReservationStatus.CANCELED, 1).needsAction(LocalDateTime.of(2026, 11, 28, 9, 0))).isFalse();
    }

    @Test
    @DisplayName("assertChangeable: RESERVED만, 상태가 먼저, 그다음 version (08 18·19번 공통 순서)")
    void assertChangeableOrder() {
        loaded(ReservationStatus.RESERVED, 2).assertChangeable(2); // 통과
        assertCode(() -> loaded(ReservationStatus.COMPLETED, 1).assertChangeable(0), ErrorCode.INVALID_STATUS_TRANSITION);
        assertCode(() -> loaded(ReservationStatus.CANCELED, 1).assertChangeable(1), ErrorCode.INVALID_STATUS_TRANSITION);
        assertCode(() -> loaded(ReservationStatus.RESERVED, 2).assertChangeable(1), ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    @DisplayName("change: 값이 있는 것만 바꾼다(null은 그대로), 메모는 빈 문자열이면 비운다, 고객·차량·담당은 그대로")
    void changeOnlyGivenFields() {
        Reservation r = Reservation.register(1L, 10L, AT, false, "메모", 7L);
        ReflectionTestUtils.setField(r, "version", 0);

        r.change(NOW, null, null, null);
        assertThat(r.getReservedAt()).isEqualTo(AT);
        assertThat(r.getPurposeMemo()).isEqualTo("메모");

        r.change(NOW, AT.plusDays(1), true, "새 메모");
        assertThat(r.getReservedAt()).isEqualTo(AT.plusDays(1));
        assertThat(r.isPurposeOther()).isTrue();
        assertThat(r.getPurposeMemo()).isEqualTo("새 메모");
        assertThat(r.getVehicleId()).isEqualTo(10L);
        assertThat(r.getAssigneeStaffId()).isEqualTo(7L);

        r.change(NOW, null, null, "");
        assertThat(r.getPurposeMemo()).isNull();
    }

    @Test
    @DisplayName("change는 수정 시각을 항상 앞으로 민다 — 목적만 바뀌어도 행이 바뀐 것이 되어 @Version이 오르게 한다(R11). 같은 초면 1초 앞으로")
    void changeAlwaysAdvancesUpdatedAt() {
        Reservation r = Reservation.register(1L, 10L, AT, false, null, 7L);
        ReflectionTestUtils.setField(r, "version", 0);
        ReflectionTestUtils.setField(r, "updatedAt", NOW.minusDays(1));

        r.change(NOW, null, null, null); // 아무 칼럼 값도 안 바뀌는 변경(목적 행만 바뀌는 경우)
        assertThat(ReflectionTestUtils.getField(r, "updatedAt")).isEqualTo(NOW);

        r.change(NOW, null, null, null); // 같은 초에 다시
        assertThat(ReflectionTestUtils.getField(r, "updatedAt")).isEqualTo(NOW.plusSeconds(1));
    }
}
