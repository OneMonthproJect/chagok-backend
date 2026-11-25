package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.wanted.chagok.global.config.ClockConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시연 시드({@code db/seed/demo_seed.sql})를 적용한 DB에서 <b>시드 계정으로 로그인해 실제 HTTP로</b> 예약 API 15·16·18·19를 호출한다
 * (07 7.3·7.5, 08 15·16번 예시, 04 R1~R4·R9·R11). 보안 필터·JWT·Controller·Service·실제 customer·item 구현·MySQL이 모두 연결된다.
 * 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 시드는 {@code DemoSeedTest}와 같은 방식으로 테스트 트랜잭션 안에서
 * 실행하고 끝나면 롤백한다.
 *
 * <p>시드의 "오늘"은 MySQL 서버의 {@code CURDATE()}이고 앱의 "오늘"은 {@code Clock}(Asia/Seoul)이다. 두 날짜가 다른 시간대(예: UTC 서버에서
 * 한국 시간 자정~오전 9시)에는 "오늘 예약"의 뜻이 어긋나 결과가 달라지므로 그 구간에서는 <b>통과가 아니라 건너뜀</b>으로 남긴다.
 *
 * <p>LIMIT: 한 트랜잭션 안이라 서로 다른 트랜잭션의 동시 요청(R8·R10 둘째 경우)은 확인하지 못한다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReservationSeedHttpDbTest {

    private static final FileSystemResource SEED = new FileSystemResource("db/seed/demo_seed.sql");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private LocalDate today;
    private String ownerA;
    private String ownerB;
    private long shopA;
    private long shopB;

    @BeforeEach
    void applySeedAndLogin() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        ScriptUtils.executeSqlScript(connection, new EncodedResource(SEED, StandardCharsets.UTF_8));

        LocalDate seedToday = LocalDate.parse(jdbc.queryForObject("SELECT DATE_FORMAT(CURDATE(), '%Y-%m-%d')", String.class));
        today = LocalDate.now(ClockConfig.SEOUL);
        assumeTrue(seedToday.equals(today),
            "시드의 오늘(MySQL CURDATE " + seedToday + ")과 앱의 오늘(Asia/Seoul " + today + ")이 달라 이 테스트는 건너뜀 — 통과가 아님");

        shopA = jdbc.queryForObject("SELECT id FROM shop WHERE name = '차곡카센터'", Long.class);
        shopB = jdbc.queryForObject("SELECT id FROM shop WHERE name = '바른카센터'", Long.class);
        ownerA = login("owner_a");
        ownerB = login("owner_b");
    }

    // ------------------------------------------------------------------ 인증

    @Test
    @DisplayName("토큰 없이 호출하면 401 AUTH_REQUIRED, 정비사 계정(mech_a)도 조회할 수 있다")
    void authentication() throws Exception {
        mvc.perform(get("/api/reservations").param("from", today.toString()).param("to", today.toString()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        mvc.perform(patch("/api/reservations/1").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
            .andExpect(status().isUnauthorized());

        String mechanic = login("mech_a");
        mvc.perform(get("/api/reservations").header("Authorization", mechanic)
                .param("from", today.toString()).param("to", today.toString()))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 기간 조회 (API 15) — 07 7.3·7.5, 08 15번 예시

    @Test
    @DisplayName("7.5 오늘 예약 2건: 78라0004 10:00(엔진오일)·22사0007 14:30(기타), 시각 순, 연락처는 전체 번호, assignee 필드 없음")
    void todaysTwoReservations() throws Exception {
        mvc.perform(get("/api/reservations").header("Authorization", ownerA)
                .param("from", today.toString()).param("to", today.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.from").value(today.toString()))
            .andExpect(jsonPath("$.data.to").value(today.toString()))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].plateNumber").value("78라0004"))
            .andExpect(jsonPath("$.data.items[0].reservedAt").value(today + "T10:00:00"))
            .andExpect(jsonPath("$.data.items[0].status").value("RESERVED"))
            .andExpect(jsonPath("$.data.items[0].version").value(0))
            .andExpect(jsonPath("$.data.items[0].purposeSummary").value("엔진오일"))
            .andExpect(jsonPath("$.data.items[0].assignee").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].phone").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("*"))))
            .andExpect(jsonPath("$.data.items[1].plateNumber").value("22사0007"))
            .andExpect(jsonPath("$.data.items[1].reservedAt").value(today + "T14:30:00"))
            .andExpect(jsonPath("$.data.items[1].purposeSummary").value("기타"));
    }

    @Test
    @DisplayName("08 15번 예시: 어제 15:00 예약(55차0010, 배터리)은 '처리 필요'(needsAction true), D+2 예약은 34나0002")
    void overdueAndUpcoming() throws Exception {
        mvc.perform(get("/api/reservations").header("Authorization", ownerA)
                .param("from", today.minusDays(1).toString()).param("to", today.minusDays(1).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].plateNumber").value("55차0010"))
            .andExpect(jsonPath("$.data.items[0].reservedAt").value(today.minusDays(1) + "T15:00:00"))
            .andExpect(jsonPath("$.data.items[0].needsAction").value(true))
            .andExpect(jsonPath("$.data.items[0].purposeSummary").value("배터리"));
        mvc.perform(get("/api/reservations").header("Authorization", ownerA)
                .param("from", today.plusDays(2).toString()).param("to", today.plusDays(2).toString()))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].plateNumber").value("34나0002"))
            .andExpect(jsonPath("$.data.items[0].needsAction").value(false));
    }

    @Test
    @DisplayName("전 기간(어제~D+2)은 시드 4건이 시각 순으로, 기간 규칙 위반(from>to, 43일)은 400, B 정비소 토큰에는 A 예약이 보이지 않는다 (X7)")
    void periodAndIsolation() throws Exception {
        mvc.perform(get("/api/reservations").header("Authorization", ownerA)
                .param("from", today.minusDays(1).toString()).param("to", today.plusDays(2).toString()))
            .andExpect(jsonPath("$.data.items.length()").value(4))
            .andExpect(jsonPath("$.data.items[0].plateNumber").value("55차0010"))
            .andExpect(jsonPath("$.data.items[3].plateNumber").value("34나0002"));
        mvc.perform(get("/api/reservations").header("Authorization", ownerA)
                .param("from", today.plusDays(1).toString()).param("to", today.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/reservations").header("Authorization", ownerA)
                .param("from", today.toString()).param("to", today.plusDays(42).toString()))
            .andExpect(status().isBadRequest());

        mvc.perform(get("/api/reservations").header("Authorization", ownerB)
                .param("from", today.minusDays(1).toString()).param("to", today.plusDays(2).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ------------------------------------------------------------------ 등록·변경·완료 (API 16·19·18)

    @Test
    @DisplayName("R3 예약이 있는 차량(78라0004)에 또 등록 → 409 + 기존 예약 번호, R4 어제 이전 '처리 필요' 예약만 있는 차량(55차0010)은 등록 성공")
    void activeReservationRules() throws Exception {
        long vehicleWithReservation = vehicleId(shopA, "78라0004");
        long existingReservation = jdbc.queryForObject(
            "SELECT id FROM reservation WHERE shop_id = ? AND vehicle_id = ?", Long.class, shopA, vehicleWithReservation);

        mvc.perform(createRequest(ownerA, vehicleWithReservation, today.plusDays(4) + "T10:00:00", ""))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("RESERVATION_ACTIVE_EXISTS"))
            .andExpect(jsonPath("$.error.details.reservationId").value(existingReservation));

        mvc.perform(createRequest(ownerA, vehicleId(shopA, "55차0010"), today.plusDays(4) + "T10:00:00", ""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("RESERVED"))
            .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("등록 오류: 30분 단위 아님(R1) 400, 어제 날짜(R2) RESERVATION_PAST_DATE, B 정비소 차량(X4) 404, B 정비소 항목(X11) 400 — 아무것도 저장되지 않는다")
    void createErrors() throws Exception {
        long vehicle = vehicleId(shopA, "66카0011");
        int before = reservationCount(shopA);

        mvc.perform(createRequest(ownerA, vehicle, today.plusDays(3) + "T10:15:00", ""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(createRequest(ownerA, vehicle, today.minusDays(1) + "T10:00:00", ""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("RESERVATION_PAST_DATE"));
        mvc.perform(createRequest(ownerA, vehicleId(shopB, "12가0001"), today.plusDays(3) + "T10:00:00", ""))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        long itemOfB = jdbc.queryForObject("SELECT id FROM service_item WHERE shop_id = ? AND code = 'ENGINE_OIL'", Long.class, shopB);
        mvc.perform(createRequest(ownerA, vehicle, today.plusDays(3) + "T10:00:00", ",\"purpose\":{\"itemIds\":[" + itemOfB + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("purpose.itemIds"));

        assertThat(reservationCount(shopA)).isEqualTo(before);
    }

    @Test
    @DisplayName("등록 → 변경(R11: 목적만 바꿔도 version 1) → 낡은 version 409 → 방문 완료(version 2) → 완료 뒤 변경·재완료 409 (R9), B 토큰은 404")
    void createUpdateCompleteFlow() throws Exception {
        long vehicle = vehicleId(shopA, "66카0011");
        long oil = jdbc.queryForObject("SELECT id FROM service_item WHERE shop_id = ? AND code = 'ENGINE_OIL'", Long.class, shopA);

        String created = mvc.perform(createRequest(ownerA, vehicle, today.plusDays(5) + "T11:30:00",
                ",\"purpose\":{\"itemIds\":[" + oil + "],\"other\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(0))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = ((Number) JsonPath.read(created, "$.data.reservationId")).longValue();

        mvc.perform(get("/api/reservations").header("Authorization", ownerA)
                .param("from", today.plusDays(5).toString()).param("to", today.plusDays(5).toString()))
            .andExpect(jsonPath("$.data.items[0].reservationId").value(id))
            .andExpect(jsonPath("$.data.items[0].plateNumber").value("66카0011"))
            .andExpect(jsonPath("$.data.items[0].purposeSummary").value("엔진오일"));

        mvc.perform(patchRequest(ownerA, id, "{\"version\":0,\"purpose\":{\"itemIds\":[],\"other\":true,\"memo\":\"소음\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.reservedAt").value(today.plusDays(5) + "T11:30:00"));
        mvc.perform(patchRequest(ownerA, id, "{\"version\":0,\"purpose\":{\"other\":false}}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
        mvc.perform(patchRequest(ownerA, id, "{\"version\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        mvc.perform(patchRequest(ownerB, id, "{\"version\":1,\"purpose\":{\"other\":false}}"))
            .andExpect(status().isNotFound());
        mvc.perform(completeRequest(ownerB, id, 1)).andExpect(status().isNotFound());

        mvc.perform(completeRequest(ownerA, id, 1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.vehicleId").value(vehicle));
        mvc.perform(patchRequest(ownerA, id, "{\"version\":2,\"purpose\":{\"other\":false}}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
        mvc.perform(completeRequest(ownerA, id, 2))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
    }

    // ------------------------------------------------------------------ helpers

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"1234\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + JsonPath.<String>read(body, "$.data.accessToken");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createRequest(
            String token, long vehicleId, String reservedAt, String extraJson) {
        return post("/api/reservations").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"vehicleId\":" + vehicleId + ",\"reservedAt\":\"" + reservedAt + "\"" + extraJson + "}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchRequest(String token, long id, String body) {
        return patch("/api/reservations/" + id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder completeRequest(String token, long id, int version) {
        return post("/api/reservations/" + id + "/complete").header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}");
    }

    private long vehicleId(long shopId, String plate) {
        return jdbc.queryForObject("SELECT id FROM vehicle WHERE shop_id = ? AND plate_number = ?", Long.class, shopId, plate);
    }

    private int reservationCount(long shopId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE shop_id = ?", Integer.class, shopId);
    }
}
