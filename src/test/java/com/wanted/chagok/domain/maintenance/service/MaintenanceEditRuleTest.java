package com.wanted.chagok.domain.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 정비 기록 수정 권한·기간 판정 한 곳 (04 H3·H10·H11, 06 7.5). */
class MaintenanceEditRuleTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-25");

    @Test
    @DisplayName("H11: 사장은 담당이 아니어도, 기준일이 아무리 오래돼도 수정한다")
    void owner_alwaysAllowed() {
        assertThatCode(() -> MaintenanceEditRule.requireAllowed(true, false, TODAY, TODAY.minusYears(1)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("H10: 담당 정비사는 기준일 + 3일(3일째)까지 수정할 수 있다")
    void mechanic_withinThreeDays() {
        assertThatCode(() -> MaintenanceEditRule.requireAllowed(false, true, TODAY, TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> MaintenanceEditRule.requireAllowed(false, true, TODAY, TODAY.minusDays(3)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("H10: 담당 정비사도 4일째부터는 403 EDIT_PERIOD_EXPIRED")
    void mechanic_afterThreeDays_expired() {
        assertThatThrownBy(() -> MaintenanceEditRule.requireAllowed(false, true, TODAY, TODAY.minusDays(4)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.EDIT_PERIOD_EXPIRED));
    }

    @Test
    @DisplayName("H3: 담당이 아닌 정비사는 403 FORBIDDEN이고, 기간이 지났어도 담당 여부를 먼저 본다")
    void mechanic_notAssignee_forbiddenFirst() {
        assertThatThrownBy(() -> MaintenanceEditRule.requireAllowed(false, false, TODAY, TODAY))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> MaintenanceEditRule.requireAllowed(false, false, TODAY, TODAY.minusDays(30)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
