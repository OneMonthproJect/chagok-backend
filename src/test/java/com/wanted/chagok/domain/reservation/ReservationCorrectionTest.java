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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 지난 예약 정정(API 40)의 Entity 규칙 (R25·R26, D-53): 정정할 수 있는 예약인지, 상태 허용표, 메모 정정. DB 없음. */
class ReservationCorrectionTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 20, 10, 0);

    private static Reservation loaded(ReservationStatus status, int version, String memo) {
        Reservation r = Reservation.register(1L, 10L, AT, false, memo, 7L, 7L);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", version);
        return r;
    }

    private static void assertTransition(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"COMPLETED", "CANCELED", "NO_SHOW"})
    @DisplayName("끝난 예약(완료·취소·노쇼)은 맞는 version이면 정정 대상이다")
    void finishedReservationIsCorrectable(ReservationStatus status) {
        loaded(status, 3, null).assertCorrectable(3);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"RESERVED", "IN_PROGRESS"})
    @DisplayName("예약·진행중은 정정 대상이 아니다 → 409 INVALID_STATUS_TRANSITION (\"처리 필요\"의 정리는 18·20·21)")
    void unfinishedIsNotCorrectable(ReservationStatus status) {
        assertTransition(() -> loaded(status, 0, null).assertCorrectable(0));
    }

    @Test
    @DisplayName("상태가 version보다 먼저 — 정정 대상이 아닌데 낡은 version이면 상태 오류, 대상인데 낡은 version이면 409 CONCURRENT_MODIFICATION")
    void statusBeforeVersion() {
        assertTransition(() -> loaded(ReservationStatus.RESERVED, 1, null).assertCorrectable(0));
        assertThatThrownBy(() -> loaded(ReservationStatus.NO_SHOW, 1, null).assertCorrectable(0))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }

    @ParameterizedTest
    @CsvSource({"CANCELED,NO_SHOW", "NO_SHOW,CANCELED", "CANCELED,COMPLETED", "NO_SHOW,COMPLETED"})
    @DisplayName("R25 허용표: 취소→노쇼, 노쇼→취소, 취소→완료, 노쇼→완료")
    void allowedTransitions(ReservationStatus from, ReservationStatus to) {
        Reservation r = loaded(from, 0, null);

        r.correctStatus(to);

        assertThat(r.getStatus()).isEqualTo(to);
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,CANCELED", "COMPLETED,NO_SHOW", "COMPLETED,COMPLETED", "CANCELED,CANCELED", "NO_SHOW,NO_SHOW",
        "CANCELED,RESERVED", "NO_SHOW,IN_PROGRESS"})
    @DisplayName("R26 허용표 밖(완료→취소·노쇼, 같은 상태, 예약·진행중으로 정정) → 409 INVALID_STATUS_TRANSITION, 상태 그대로")
    void forbiddenTransitions(ReservationStatus from, ReservationStatus to) {
        Reservation r = loaded(from, 0, null);

        assertTransition(() -> r.correctStatus(to));
        assertThat(r.getStatus()).isEqualTo(from);
    }

    @Test
    @DisplayName("메모 정정: 값이 바뀌고, 빈 문자열이면 비운다 (19와 같음)")
    void correctMemo() {
        Reservation r = loaded(ReservationStatus.COMPLETED, 0, "기존 메모");

        r.correctMemo("고친 메모");
        assertThat(r.getPurposeMemo()).isEqualTo("고친 메모");

        r.correctMemo("");
        assertThat(r.getPurposeMemo()).isNull();
    }
}
