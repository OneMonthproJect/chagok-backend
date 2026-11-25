package com.wanted.chagok.screen.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 시연 시드({@code db/seed/demo_seed.sql})를 적용한 DB에서 <b>시드 계정으로 로그인해 실제 HTTP로</b> 예약 상세 패널(API 17)을 호출한다
 * (07 7.2·7.5, 08 17번 예시, X3). 보안 필터·JWT·Controller·실제 reservation·customer·maintenance·item 구현·MySQL이 모두 연결된다.
 * 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 시드는 {@code DemoSeedTest}와 같은 방식으로
 * 테스트 트랜잭션 안에서 실행하고 끝나면 롤백한다.
 *
 * <p>기대값은 시드 파일의 값이다. 예약 4(55차0010, 어제 15:00, 배터리)가 08 17번 예시와 같은 차량·고객이다. 날짜는 시드의 오늘(서울 날짜)
 * 기준 상대값이라 날짜 자체는 고정하지 않고 구조·상태·횟수를 확인한다.
 *
 * <p>B 정비소 예약은 시드에 없어 X3 확인은 B 정비소 계정으로 A 예약을 조회하는 쪽으로 한다(D-63 B12: B 예약은 테스트 픽스처).
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PanelSeedHttpDbTest {

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

    @BeforeEach
    void applySeedAndLogin() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        ScriptUtils.executeSqlScript(connection, new EncodedResource(SEED, StandardCharsets.UTF_8));

        // 시드의 오늘(@today)은 서울 날짜로 계산된다(07 7장, chagok-docs#110). 앱의 오늘과 같아야 한다
        LocalDate seedToday = LocalDate.parse(jdbc.queryForObject("SELECT DATE_FORMAT(@today, '%Y-%m-%d')", String.class));
        today = LocalDate.now(ClockConfig.SEOUL);
        assertThat(seedToday).as("시드의 오늘(@today)과 앱의 오늘(Asia/Seoul)").isEqualTo(today);

        shopA = jdbc.queryForObject("SELECT id FROM shop WHERE name = '차곡카센터'", Long.class);
        ownerA = login("owner_a");
        ownerB = login("owner_b");
    }

    private long reservationOf(String plate) {
        return jdbc.queryForObject("""
            SELECT r.id FROM reservation r JOIN vehicle v ON v.id = r.vehicle_id
            WHERE r.shop_id = ? AND v.plate_number = ?
            """, Long.class, shopA, plate);
    }

    @Test
    @DisplayName("토큰 없이 호출하면 401 AUTH_REQUIRED")
    void authentication() throws Exception {
        mvc.perform(get("/api/reservations/" + reservationOf("55차0010") + "/panel"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("08 17번 예시와 같은 차량: 55차0010 어제 15:00 예약 — '처리 필요', 배터리 강조, 고객 오세훈(전체 연락처·consent true), 방문 3회에 최근 2회")
    void panelOfOverdueReservation() throws Exception {
        long reservation = reservationOf("55차0010");

        mvc.perform(get("/api/reservations/" + reservation + "/panel").header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservation.reservationId").value(reservation))
            .andExpect(jsonPath("$.data.reservation.reservedAt").value(today.minusDays(1) + "T15:00:00"))
            .andExpect(jsonPath("$.data.reservation.status").value("RESERVED"))
            .andExpect(jsonPath("$.data.reservation.needsAction").value(true))
            .andExpect(jsonPath("$.data.reservation.version").value(0))
            .andExpect(jsonPath("$.data.reservation.purpose.items.length()").value(1))
            .andExpect(jsonPath("$.data.reservation.purpose.items[0].name").value("배터리"))
            .andExpect(jsonPath("$.data.reservation.purpose.other").value(false))
            .andExpect(jsonPath("$.data.reservation.purpose.memo").value(nullValue()))
            .andExpect(jsonPath("$.data.reservation.assignee").doesNotExist())
            .andExpect(jsonPath("$.data.customer.name").value("오세훈"))
            .andExpect(jsonPath("$.data.customer.phone").value("010-0000-0010"))
            .andExpect(jsonPath("$.data.customer.consent").value(true))
            .andExpect(jsonPath("$.data.vehicle.plateNumber").value("55차0010"))
            .andExpect(jsonPath("$.data.vehicle.model").value("쏘나타"))
            .andExpect(jsonPath("$.data.vehicle.modelYear").value(2018))
            .andExpect(jsonPath("$.data.vehicle.latestMileage").value(97000))
            // 방문 3회(배터리 방문, 에어클리너·에어컨필터 같은 방문, 엔진오일 방문), 최근 2회만: 엔진오일 방문 → 에어클리너·에어컨필터 방문
            .andExpect(jsonPath("$.data.totalVisitCount").value(3))
            .andExpect(jsonPath("$.data.recentVisits.length()").value(2))
            .andExpect(jsonPath("$.data.recentVisits[0].visitKey").value("5eed0000-0000-4000-8000-000000000015"))
            .andExpect(jsonPath("$.data.recentVisits[0].records[0].itemName").value("엔진오일"))
            .andExpect(jsonPath("$.data.recentVisits[1].visitKey").value("5eed0000-0000-4000-8000-000000000014"))
            .andExpect(jsonPath("$.data.recentVisits[1].records.length()").value(2));
    }

    @Test
    @DisplayName("항목 상태: 쓰는 항목 6개가 sortOrder 순(엔진오일 … 배터리)이고, 목적 항목(배터리)만 강조되며 자리를 옮기지 않는다. 배터리는 임박, 엔진오일은 여유")
    void itemStatusesInSortOrderWithHighlight() throws Exception {
        long reservation = reservationOf("55차0010");

        mvc.perform(get("/api/reservations/" + reservation + "/panel").header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.itemStatuses.length()").value(6))
            .andExpect(jsonPath("$.data.itemStatuses[0].code").value("ENGINE_OIL"))
            .andExpect(jsonPath("$.data.itemStatuses[0].status").value("AHEAD"))
            .andExpect(jsonPath("$.data.itemStatuses[0].highlighted").value(false))
            .andExpect(jsonPath("$.data.itemStatuses[5].code").value("BATTERY"))
            .andExpect(jsonPath("$.data.itemStatuses[5].status").value("SOON"))
            .andExpect(jsonPath("$.data.itemStatuses[5].highlighted").value(true))
            // 주행거리 없이 날짜 기준만 판정하므로 km 기준 사유가 나오지 않는다
            .andExpect(jsonPath("$.data.itemStatuses[5].dueReason").value(nullValue()))
            .andExpect(jsonPath("$.data.itemStatuses[5].nextDueMileage").value(nullValue()));
    }

    @Test
    @DisplayName("기타 + 메모 목적: 22사0007 오늘 14:30 예약은 purpose.other true, memo '에어컨 냄새', 항목 없음이라 강조되는 항목이 없다")
    void panelOfOtherPurpose() throws Exception {
        long reservation = reservationOf("22사0007");

        mvc.perform(get("/api/reservations/" + reservation + "/panel").header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reservation.reservedAt").value(today + "T14:30:00"))
            .andExpect(jsonPath("$.data.reservation.purpose.items.length()").value(0))
            .andExpect(jsonPath("$.data.reservation.purpose.other").value(true))
            .andExpect(jsonPath("$.data.reservation.purpose.memo").value("에어컨 냄새"))
            .andExpect(jsonPath("$.data.itemStatuses[?(@.highlighted == true)]").isEmpty());
    }

    @Test
    @DisplayName("X3 A 정비소 예약을 B 토큰으로 조회하면 404 NOT_FOUND(없는 id와 같은 응답), 예약·고객 정보가 새지 않는다")
    void isolation() throws Exception {
        long reservation = reservationOf("55차0010");

        mvc.perform(get("/api/reservations/" + reservation + "/panel").header("Authorization", ownerB))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.data").value(nullValue()));
        mvc.perform(get("/api/reservations/999999999/panel").header("Authorization", ownerA))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"1234\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + JsonPath.<String>read(body, "$.data.accessToken");
    }
}
