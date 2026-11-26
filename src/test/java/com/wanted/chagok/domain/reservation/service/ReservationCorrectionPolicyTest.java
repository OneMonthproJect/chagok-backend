package com.wanted.chagok.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 지난 예약 정정(API 40)의 권한 판정 (R27~R29, D-53): 상태는 사장만, 메모는 사장 또는 담당 정비사의 기간 안. DB 없음. */
class ReservationCorrectionPolicyTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 20);

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    @DisplayName("사장은 상태·메모 정정에 기간·담당 제한이 없다 (R25, R28 사장 4일째 성공)")
    void ownerIsNeverRestricted() {
        LocalDate longAfter = DATE.plusDays(30);
        assertThatCode(() -> ReservationCorrectionPolicy.requireAllowed(true, false, true, true, longAfter, DATE))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReservationCorrectionPolicy.requireAllowed(true, false, false, true, DATE.plusDays(4), DATE))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R27 정비사의 상태 정정은 403 FORBIDDEN — 담당이어도, 상태와 같은 값을 보냈어도, 메모를 함께 보내도")
    void mechanicCannotCorrectStatus() {
        assertCode(() -> ReservationCorrectionPolicy.requireAllowed(false, true, true, false, DATE, DATE), ErrorCode.FORBIDDEN);
        assertCode(() -> ReservationCorrectionPolicy.requireAllowed(false, true, true, true, DATE, DATE), ErrorCode.FORBIDDEN);
        assertCode(() -> ReservationCorrectionPolicy.requireAllowed(false, false, true, false, DATE, DATE), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("R28 담당 정비사의 메모 정정: 예약 날짜 + 3일째까지 성공, 4일째는 403 EDIT_PERIOD_EXPIRED")
    void assigneeMemoPeriod() {
        assertThatCode(() -> ReservationCorrectionPolicy.requireAllowed(false, true, false, true, DATE, DATE)).doesNotThrowAnyException();
        assertThatCode(() -> ReservationCorrectionPolicy.requireAllowed(false, true, false, true, DATE.plusDays(3), DATE))
            .doesNotThrowAnyException();
        assertCode(() -> ReservationCorrectionPolicy.requireAllowed(false, true, false, true, DATE.plusDays(4), DATE),
            ErrorCode.EDIT_PERIOD_EXPIRED);
    }

    @Test
    @DisplayName("R29 담당이 아닌 정비사의 메모 정정은 403 FORBIDDEN — 기간 안이어도, 기간 밖이어도 담당 여부가 먼저")
    void notTheAssignee() {
        assertCode(() -> ReservationCorrectionPolicy.requireAllowed(false, false, false, true, DATE, DATE), ErrorCode.FORBIDDEN);
        assertCode(() -> ReservationCorrectionPolicy.requireAllowed(false, false, false, true, DATE.plusDays(10), DATE),
            ErrorCode.FORBIDDEN);
    }
}
