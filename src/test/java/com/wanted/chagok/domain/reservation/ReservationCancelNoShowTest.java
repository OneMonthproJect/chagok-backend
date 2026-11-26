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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 취소(API 20)·노쇼(API 21)의 Entity 상태 전이 단위 테스트 (DB 없음). R6·R10·R12·R13 중 Entity가 정하는 부분.
 * {@code @Version}의 실제 증가와 저장 시 동시 요청 충돌은 DB가 있어야 확인된다.
 */
class ReservationCancelNoShowTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 24, 10, 0);

    /** DB에서 읽어 온 것처럼 version·상태를 맞춘 예약 (저장 전에는 version이 null). */
    private static Reservation loaded(ReservationStatus status, int version) {
        Reservation r = Reservation.register(1L, 10L, AT, false, null, 7L, 7L);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", version);
        return r;
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    // ---- 취소 (20) ----

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"RESERVED", "IN_PROGRESS"})
    @DisplayName("R13 예약·진행중을 맞는 version으로 취소 → CANCELED")
    void cancel(ReservationStatus from) {
        Reservation r = loaded(from, 0);

        r.cancel(0);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"COMPLETED", "CANCELED", "NO_SHOW"})
    @DisplayName("끝난 예약(완료·취소·노쇼)을 취소 → 409 INVALID_STATUS_TRANSITION, 상태 그대로")
    void cancelFinished(ReservationStatus from) {
        Reservation r = loaded(from, 1);

        assertCode(() -> r.cancel(1), ErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(r.getStatus()).isEqualTo(from);
    }

    @Test
    @DisplayName("R10 다른 직원이 이미 끝냈고 낡은 version으로 취소 → 상태 오류가 먼저 (CONCURRENT_MODIFICATION 아님)")
    void cancelStatusBeforeVersion() {
        Reservation r = loaded(ReservationStatus.COMPLETED, 1);

        assertCode(() -> r.cancel(0), ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("R12 낡은 version으로 취소 → 409 CONCURRENT_MODIFICATION, 상태 그대로")
    void cancelStaleVersion() {
        Reservation r = loaded(ReservationStatus.RESERVED, 1);

        assertCode(() -> r.cancel(0), ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("서버보다 큰 version(음수 포함)도 400이 아니라 409 CONCURRENT_MODIFICATION")
    void cancelOtherVersions() {
        Reservation r = loaded(ReservationStatus.RESERVED, 1);

        assertCode(() -> r.cancel(5), ErrorCode.CONCURRENT_MODIFICATION);
        assertCode(() -> r.cancel(-1), ErrorCode.CONCURRENT_MODIFICATION);
    }

    // ---- 노쇼 (21) ----

    @Test
    @DisplayName("R6 시각이 지난 예약을 노쇼 → NO_SHOW")
    void noShow() {
        Reservation r = loaded(ReservationStatus.RESERVED, 0);

        r.markNoShow(0, AT.plusMinutes(1));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
    }

    @Test
    @DisplayName("R6 경계: 예약 시각과 같은 시각은 아직 안 지난 것 → 409 INVALID_STATUS_TRANSITION")
    void noShowAtExactTime() {
        Reservation r = loaded(ReservationStatus.RESERVED, 0);

        assertCode(() -> r.markNoShow(0, AT), ErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("R6 시각 전 예약을 노쇼 → 409 INVALID_STATUS_TRANSITION, 상태 그대로")
    void noShowBeforeTime() {
        Reservation r = loaded(ReservationStatus.RESERVED, 0);

        assertCode(() -> r.markNoShow(0, AT.minusMinutes(30)), ErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"IN_PROGRESS", "COMPLETED", "CANCELED", "NO_SHOW"})
    @DisplayName("예약이 아닌 상태(진행중·끝난 예약)의 노쇼 → 409 INVALID_STATUS_TRANSITION")
    void noShowNotReserved(ReservationStatus from) {
        Reservation r = loaded(from, 0);

        assertCode(() -> r.markNoShow(0, AT.plusDays(1)), ErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(r.getStatus()).isEqualTo(from);
    }

    @Test
    @DisplayName("R12 낡은 version으로 노쇼 → 409 CONCURRENT_MODIFICATION (시각 확인보다 version이 먼저)")
    void noShowStaleVersionBeforeTime() {
        Reservation r = loaded(ReservationStatus.RESERVED, 1);

        // 시각도 아직 안 지났지만 version 비교가 먼저라 CONCURRENT_MODIFICATION이다 (08 21번)
        assertCode(() -> r.markNoShow(0, AT.minusMinutes(30)), ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    @DisplayName("노쇼한 예약은 \"처리 필요\"가 아니다 (D-23: RESERVED만)")
    void noShowIsNotNeedsAction() {
        Reservation r = loaded(ReservationStatus.RESERVED, 0);
        LocalDateTime now = AT.plusHours(1);
        assertThat(r.needsAction(now)).isTrue();

        r.markNoShow(0, now);

        assertThat(r.needsAction(now)).isFalse();
    }
}
