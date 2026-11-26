package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanted.chagok.domain.customer.service.PhoneMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 연락처 마스킹 규칙 (08 0.5, D-63 B07): 예시표 그대로. */
class PhoneMaskerTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "010-0000-0001, 010-****-0001",
        "02-123-4567, 02-***-4567",
        "0311234567, 031-***-4567",
        "02-1234-5678, 02-****-5678",
        "01012345678, 010-****-5678",
        "1588-1234, ****-1234",
        "12345, *-2345",
        "1234, ****",
        "12345678, ****-5678",
        "123456789, 123-**-6789",
        "021234567, 02-***-4567",
        "1, *",
        "123, ***"
    })
    @DisplayName("숫자 자리 수에 따라 가운데를 가리고 뒤 4자리만 남긴다 (9 이상 / 5~8 / 4 이하)")
    void masksByDigitCount(String phone, String expected) {
        assertThat(PhoneMasker.mask(phone)).isEqualTo(expected);
    }

    @Test
    @DisplayName("원래 하이픈 위치와 관계없이 같은 번호면 같은 표시다")
    void ignoresHyphenPositions() {
        assertThat(PhoneMasker.mask("0100000-0001")).isEqualTo("010-****-0001");
        assertThat(PhoneMasker.mask("010-00000001")).isEqualTo("010-****-0001");
        assertThat(PhoneMasker.mask("01000000001")).isEqualTo("010-****-0001");
    }

    @Test
    @DisplayName("공백이 섞이거나 숫자가 아닌 글자가 있어도 숫자만 세고, 숫자가 없으면 내용을 돌려주지 않는다")
    void ignoresNonDigits() {
        assertThat(PhoneMasker.mask(" 010 0000 0001 ")).isEqualTo("010-****-0001");
        assertThat(PhoneMasker.mask("(010) 0000-0001")).isEqualTo("010-****-0001");
        assertThat(PhoneMasker.mask("abc")).isEmpty();
    }

    @Test
    @DisplayName("빈 값(익명화 고객 등)은 그대로, 가려진 자리의 숫자는 결과에 남지 않는다")
    void emptyStaysEmptyAndNothingElseLeaks() {
        assertThat(PhoneMasker.mask("")).isEmpty();
        assertThat(PhoneMasker.mask(null)).isNull();
        assertThat(PhoneMasker.mask("010-0000-0001").replaceAll("[^0-9]", "")).isEqualTo("0100001");
        assertThat(PhoneMasker.mask("010-1234-5678")).doesNotContain("1234");
    }
}
