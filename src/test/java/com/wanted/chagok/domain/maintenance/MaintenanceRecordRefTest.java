package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 거절·점검 주의 꼬리표 판정 재료 (04 F-CYC-02, #110). 값 이름 비교는 maintenance 안의 이 두 메서드 한 곳에만 있고,
 * screen 세 곳은 이 메서드만 부른다. DB 없음.
 */
class MaintenanceRecordRefTest {

    private static MaintenanceRecordRef ref(String type, String inspectResult) {
        return new MaintenanceRecordRef(1L, 2L, 3L, LocalDate.of(2026, 10, 24), null, type,
            RecordType.valueOf(type).resetsCycle(), LocalDateTime.of(2026, 10, 24, 11, 0), null, inspectResult);
    }

    @Test
    @DisplayName("isDecline: 거절 구분일 때만 true")
    void isDecline() {
        assertThat(ref("DECLINE", null).isDecline()).isTrue();
        assertThat(ref("REPLACE", null).isDecline()).isFalse();
        assertThat(ref("EXTERNAL", null).isDecline()).isFalse();
        assertThat(ref("INSPECT", "CAUTION").isDecline()).isFalse();
    }

    @Test
    @DisplayName("isInspectCaution: 점검이고 결과가 CAUTION일 때만 true (양호·결과 없음·다른 구분은 false)")
    void isInspectCaution() {
        assertThat(ref("INSPECT", "CAUTION").isInspectCaution()).isTrue();
        assertThat(ref("INSPECT", "GOOD").isInspectCaution()).isFalse();
        assertThat(ref("INSPECT", null).isInspectCaution()).isFalse();
        assertThat(ref("REPLACE", null).isInspectCaution()).isFalse();
        assertThat(ref("DECLINE", null).isInspectCaution()).isFalse();
    }

    @Test
    @DisplayName("08 0.4 구분 표시값은 RecordType 이름과 같다 — 이름이 바뀌면 이 테스트가 먼저 깨진다")
    void recordTypeNamesAreTheSpecValues() {
        assertThat(RecordType.values()).extracting(Enum::name)
            .containsExactlyInAnyOrder("REPLACE", "INSPECT", "EXTERNAL", "DECLINE");
    }
}
