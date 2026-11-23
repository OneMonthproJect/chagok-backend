package com.wanted.chagok.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 08 0.1 쪽 나누기 경계. DB 없이 돈다. */
class PageLimitTest {

    @Test
    @DisplayName("생략 → page 0·size 20")
    void defaults() {
        assertThat(PageLimit.of(null, null)).isEqualTo(new PageLimit(0, 20));
    }

    @Test
    @DisplayName("경계: size 1·50은 그대로, 51·1000은 50으로 줄임")
    void sizeBounds() {
        assertThat(PageLimit.of(0, 1).size()).isEqualTo(1);
        assertThat(PageLimit.of(0, 50).size()).isEqualTo(50);
        assertThat(PageLimit.of(0, 51).size()).isEqualTo(50);
        assertThat(PageLimit.of(3, 1000)).isEqualTo(new PageLimit(3, 50));
    }

    @Test
    @DisplayName("page 음수, size 0·음수 → 400 INVALID_INPUT + 그 필드")
    void invalidValues() {
        assertThatThrownBy(() -> PageLimit.of(-1, 20)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("page");
        });
        assertThatThrownBy(() -> PageLimit.of(0, 0)).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("size"));
        assertThatThrownBy(() -> PageLimit.of(0, -5)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("of()를 거치지 않고 범위 밖 값으로 만들 수 없다")
    void constructorGuardsInvariant() {
        assertThatThrownBy(() -> new PageLimit(0, 51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageLimit(-1, 20)).isInstanceOf(IllegalArgumentException.class);
    }
}
