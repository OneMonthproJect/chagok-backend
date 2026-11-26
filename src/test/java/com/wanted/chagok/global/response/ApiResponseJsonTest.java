package com.wanted.chagok.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.JacksonConfig;
import com.wanted.chagok.global.config.JpaAuditingConfig;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.response.ApiResponse.FieldErrorDetail;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 08 0.2 공통 응답 모양과 08 0.1 날짜·일시 형식, Clock·Auditing 시각. DB 없이 돈다. */
class ApiResponseJsonTest {

    private final JsonMapper mapper = mapper();

    private static JsonMapper mapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().localDateTimeFormat().customize(builder);
        return builder.build();
    }

    @Test
    @DisplayName("성공: { success: true, data, error: null } — error 필드를 빼지 않는다")
    void success() {
        assertThat(mapper.writeValueAsString(ApiResponse.ok(Map.of("id", 1))))
            .isEqualTo("{\"success\":true,\"data\":{\"id\":1},\"error\":null}");
    }

    @Test
    @DisplayName("실패: data null, error { code, message, fieldErrors(빈 배열), details(null) }")
    void failureWithoutFields() {
        assertThat(mapper.writeValueAsString(ApiResponse.fail(ErrorCode.AUTH_REQUIRED)))
            .isEqualTo("{\"success\":false,\"data\":null,\"error\":{\"code\":\"AUTH_REQUIRED\","
                + "\"message\":\"로그인이 필요합니다.\",\"fieldErrors\":[],\"details\":null}}");
    }

    @Test
    @DisplayName("실패: INVALID_INPUT fieldErrors, 409 details.reservationId (08 0.2 예시 모양)")
    void failureWithFieldsAndDetails() {
        assertThat(mapper.writeValueAsString(ApiResponse.fail(ErrorCode.INVALID_INPUT,
            List.of(new FieldErrorDetail("reservedAt", "30분 단위로 입력해 주세요.")), null)))
            .contains("\"fieldErrors\":[{\"field\":\"reservedAt\",\"reason\":\"30분 단위로 입력해 주세요.\"}]")
            .contains("\"details\":null");
        assertThat(mapper.writeValueAsString(ApiResponse.fail(ErrorCode.RESERVATION_ACTIVE_EXISTS, List.of(),
            Map.of("reservationId", 3))))
            .contains("\"code\":\"RESERVATION_ACTIVE_EXISTS\"")
            .contains("\"details\":{\"reservationId\":3}");
    }

    @Test
    @DisplayName("ErrorCode = 08 0.3 P0-핵심 11개 + P0-나머지 3개, HTTP 상태 1:1 (P1 코드는 아직 없다)")
    void errorCodesMatchSpec() {
        assertThat(ErrorCode.values()).extracting(c -> c.name() + ":" + c.status().value()).containsExactly(
            "AUTH_REQUIRED:401", "LOGIN_FAILED:401", "FORBIDDEN:403", "NOT_FOUND:404", "INVALID_INPUT:400",
            "RESERVATION_PAST_DATE:400", "RESERVATION_ACTIVE_EXISTS:409", "INVALID_STATUS_TRANSITION:409",
            "CONFLICT:409", "CONCURRENT_MODIFICATION:409", "INTERNAL_ERROR:500",
            "MECHANIC_SLOT_CONFLICT:409", "ASSIGNEE_CHANGE_CLOSED:409", "EDIT_PERIOD_EXPIRED:403");
    }

    @Test
    @DisplayName("P0-나머지 코드의 문구, MECHANIC_SLOT_CONFLICT는 details.reservationId를 싣는다 (08 0.2·0.3)")
    void remainingCodesMessageAndDetails() {
        // 08 16 JSON 예시의 message와 같다. 나머지 두 개는 08에 문구가 없어 팀원이 정한 안이다.
        assertThat(ErrorCode.MECHANIC_SLOT_CONFLICT.message())
            .isEqualTo("이 정비사는 해당 시각에 이미 예약이 있습니다. 다른 시각이나 다른 담당을 선택해 주세요.");
        assertThat(ErrorCode.ASSIGNEE_CHANGE_CLOSED.message()).isEqualTo("지금은 담당 정비사를 바꿀 수 없습니다.");
        assertThat(ErrorCode.EDIT_PERIOD_EXPIRED.message()).isEqualTo("수정할 수 있는 기간이 지났습니다.");
        assertThat(mapper.writeValueAsString(ApiResponse.fail(ErrorCode.MECHANIC_SLOT_CONFLICT, List.of(),
            Map.of("reservationId", 7))))
            .contains("\"code\":\"MECHANIC_SLOT_CONFLICT\"")
            .contains("\"fieldErrors\":[]")
            .contains("\"details\":{\"reservationId\":7}");
    }

    @Test
    @DisplayName("일시 \"2026-11-24T10:00:00\"(시간대·소수 초 없음), 날짜 \"2026-11-24\" (08 0.1)")
    void dateTimeFormat() {
        String json = mapper.writeValueAsString(Map.of(
            "at", LocalDateTime.of(2026, 11, 24, 10, 0, 0, 123_456_789),
            "day", LocalDate.of(2026, 11, 24)));
        assertThat(json).contains("\"at\":\"2026-11-24T10:00:00\"").contains("\"day\":\"2026-11-24\"")
            .doesNotContain("Z").doesNotContain("+09").doesNotContain(".123");
    }

    @Test
    @DisplayName("Clock은 Asia/Seoul·초 단위, Auditing 시각은 그 Clock의 서울 시각")
    void clockAndAuditing() {
        Clock clock = new ClockConfig().clock();
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(clock.instant().getNano()).isZero();

        Clock fixed = Clock.fixed(Instant.parse("2026-11-24T01:00:00Z"), ClockConfig.SEOUL);
        assertThat(new JpaAuditingConfig().auditingDateTimeProvider(fixed).getNow())
            .contains(LocalDateTime.of(2026, 11, 24, 10, 0, 0));
    }
}
