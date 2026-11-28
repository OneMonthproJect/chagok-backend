package com.wanted.chagok.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 08 0.1 주행거리 경계 (D-63 B03). DB 없이 돈다. API별 오류 문구·필드는 각 Controller·Service 테스트가 본다. */
class MileageRangeTest {

    @Test
    @DisplayName("경계: 0·2,000,000은 범위 안, -1·2,000,001은 범위 밖")
    void bounds() {
        assertThat(MileageRange.isOutOfRange(0)).isFalse();
        assertThat(MileageRange.isOutOfRange(2_000_000)).isFalse();
        assertThat(MileageRange.isOutOfRange(-1)).isTrue();
        assertThat(MileageRange.isOutOfRange(2_000_001)).isTrue();
    }

    @Test
    @DisplayName("null은 입력하지 않은 것이라 범위 밖이 아니다 (필수 여부는 호출하는 쪽)")
    void nullIsNotOutOfRange() {
        assertThat(MileageRange.isOutOfRange(null)).isFalse();
    }
}
