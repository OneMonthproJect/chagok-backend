package com.wanted.chagok.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 담당 재배정 정책(R24)의 (a)~(f) 중 상태가 아닌 날짜·역할·사유 판정. DB 없음. 진행중(e)은 service가 앞의 상태 확인에서 막는다. */
class AssigneeChangePolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    @DisplayName("R24(a) 예약 날짜 전날까지는 정비사·사장 모두 가능하고 사유는 없어도 된다")
    void beforeTheDay() {
        assertThatCode(() -> AssigneeChangePolicy.requireAllowed(false, TODAY, TOMORROW, null)).doesNotThrowAnyException();
        assertThatCode(() -> AssigneeChangePolicy.requireAllowed(true, TODAY, TOMORROW, null)).doesNotThrowAnyException();
        assertThatCode(() -> AssigneeChangePolicy.requireAllowed(false, TODAY, TODAY.plusDays(30), "사유")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R24(b) 당일 정비사는 사유가 있어도 409 ASSIGNEE_CHANGE_CLOSED")
    void sameDayMechanic() {
        assertCode(() -> AssigneeChangePolicy.requireAllowed(false, TODAY, TODAY, null), ErrorCode.ASSIGNEE_CHANGE_CLOSED);
        assertCode(() -> AssigneeChangePolicy.requireAllowed(false, TODAY, TODAY, "급한 일"), ErrorCode.ASSIGNEE_CHANGE_CLOSED);
    }

    @Test
    @DisplayName("R24(c) 당일 사장은 사유가 있으면 가능")
    void sameDayOwnerWithReason() {
        assertThatCode(() -> AssigneeChangePolicy.requireAllowed(true, TODAY, TODAY, "고객 요청")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R24(d) 당일 사장이 사유 없이(없음·빈 문자열·공백) → 400 INVALID_INPUT(reassignReason)")
    void sameDayOwnerWithoutReason() {
        for (String reason : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> AssigneeChangePolicy.requireAllowed(true, TODAY, TODAY, reason))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.fieldErrors()).extracting("field").containsExactly("reassignReason");
                });
        }
    }

    @Test
    @DisplayName("R24(f) 지난 날짜는 사장도 사유가 있어도 409 ASSIGNEE_CHANGE_CLOSED")
    void pastDate() {
        assertCode(() -> AssigneeChangePolicy.requireAllowed(true, TODAY, YESTERDAY, "사유"), ErrorCode.ASSIGNEE_CHANGE_CLOSED);
        assertCode(() -> AssigneeChangePolicy.requireAllowed(false, TODAY, YESTERDAY, null), ErrorCode.ASSIGNEE_CHANGE_CLOSED);
    }
}
