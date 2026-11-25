package com.wanted.chagok.screen.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.wanted.chagok.global.config.ClockConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * 시연 시드({@code db/seed/demo_seed.sql})를 적용한 DB에서 <b>시드 계정으로 로그인해 실제 HTTP로</b> 항목 상태(API 10)를 호출한다
 * (04 F-VIS-01 시나리오 B·T8, 07 7.2 기대 상태, 08 X6). 이슈 #25 완료 정의의 「시드로 미확인·여유·임박·도래가 골고루 보임」·「기록이 남지
 * 않음」·DB 기준 격리를 확인한다. 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 시드는
 * {@code ReservationSeedHttpDbTest}와 같은 방식으로 테스트 트랜잭션 안에서 실행하고 끝나면 롤백한다. 기대 상태는 시드 파일 머리말(07 7.2)이다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemStatusSeedHttpDbTest {

    private static final FileSystemResource SEED = new FileSystemResource("db/seed/demo_seed.sql");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private jakarta.persistence.EntityManager em;

    private JdbcTemplate jdbc;
    private long shopA;
    private long shopB;
    private String ownerA;
    private String ownerB;

    @BeforeEach
    void applySeedAndLogin() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        ScriptUtils.executeSqlScript(connection, new EncodedResource(SEED, StandardCharsets.UTF_8));

        LocalDate seedToday = LocalDate.parse(jdbc.queryForObject("SELECT DATE_FORMAT(@today, '%Y-%m-%d')", String.class));
        assertThat(seedToday).as("시드의 오늘(@today)과 앱의 오늘(Asia/Seoul)").isEqualTo(LocalDate.now(ClockConfig.SEOUL));

        shopA = jdbc.queryForObject("SELECT id FROM shop WHERE name = '차곡카센터'", Long.class);
        shopB = jdbc.queryForObject("SELECT id FROM shop WHERE name = '바른카센터'", Long.class);
        ownerA = login("owner_a");
        ownerB = login("owner_b");
    }

    private long vehicleA(String plate) {
        return jdbc.queryForObject("SELECT id FROM vehicle WHERE shop_id = ? AND plate_number = ?", Long.class, shopA, plate);
    }

    private String itemStatusBody(String token, long vehicleId, String mileage) throws Exception {
        var request = get("/api/vehicles/" + vehicleId + "/item-status").header("Authorization", token);
        if (mileage != null) {
            request = request.param("mileage", mileage);
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String statusOf(String body, String code) {
        List<String> found = JsonPath.read(body, "$.data.items[?(@.code=='" + code + "')].status");
        assertThat(found).as("항목 " + code).hasSize(1);
        return found.get(0);
    }

    private static List<String> allStatuses(String body) {
        return JsonPath.read(body, "$.data.items[*].status");
    }

    @Test
    @DisplayName("07 7.2: 시드 차량의 상태가 머리말 기대와 같다 — 도래·임박·여유·미확인이 모두 나온다")
    void seedStatesMatchSpec() throws Exception {
        // 도래: 엔진오일 (거절·점검은 기준이 아님)
        for (String plate : List.of("12가0001", "34나0002", "11바0006", "44자0009")) {
            assertThat(statusOf(itemStatusBody(ownerA, vehicleA(plate), null), "ENGINE_OIL")).as(plate).isEqualTo("DUE");
        }
        // 임박: 브레이크패드(박철수), 타이어(윤하늘, 점검은 기준이 아님), 배터리(오세훈)
        assertThat(statusOf(itemStatusBody(ownerA, vehicleA("56다0003"), null), "BRAKE_PAD")).isEqualTo("SOON");
        assertThat(statusOf(itemStatusBody(ownerA, vehicleA("33아0008"), null), "TIRE")).isEqualTo("SOON");
        assertThat(statusOf(itemStatusBody(ownerA, vehicleA("55차0010"), null), "BATTERY")).isEqualTo("SOON");
        // 여유: 최지은 엔진오일, 서민준 에어컨필터(외부 기록으로 재시작)
        assertThat(statusOf(itemStatusBody(ownerA, vehicleA("78라0004"), null), "ENGINE_OIL")).isEqualTo("AHEAD");
        assertThat(statusOf(itemStatusBody(ownerA, vehicleA("22사0007"), null), "AC_FILTER")).isEqualTo("AHEAD");
        // 미확인: 정우성·66카0011은 기록이 없어 6개 항목 모두
        for (String plate : List.of("90마0005", "66카0011")) {
            List<String> statuses = allStatuses(itemStatusBody(ownerA, vehicleA(plate), null));
            assertThat(statuses).as(plate).hasSize(6).containsOnly("UNKNOWN");
        }
    }

    @Test
    @DisplayName("A 정비소 차량 11대 전체에서 미확인·여유·임박·도래 네 상태가 모두 보이고, 항목은 6개씩 sortOrder 순이다")
    void allFourStatesAppear() throws Exception {
        List<Long> vehicles = jdbc.queryForList("SELECT id FROM vehicle WHERE shop_id = ? ORDER BY id", Long.class, shopA);
        assertThat(vehicles).hasSize(11);

        Set<String> seen = new HashSet<>();
        for (long vehicle : vehicles) {
            String body = itemStatusBody(ownerA, vehicle, null);
            List<String> codes = JsonPath.read(body, "$.data.items[*].code");
            assertThat(codes).containsExactly("ENGINE_OIL", "AIR_CLEANER", "AC_FILTER", "BRAKE_PAD", "TIRE", "BATTERY");
            seen.addAll(allStatuses(body));
        }
        assertThat(seen).containsExactlyInAnyOrder("UNKNOWN", "AHEAD", "SOON", "DUE");
    }

    @Test
    @DisplayName("T8 시나리오 B: 78라0004 엔진오일은 날짜로는 여유인데 방문 주행거리 55,000을 넣으면 km 기준 도래(KM)가 된다")
    void scenarioB_mileageMakesOilDueByKm() throws Exception {
        long vehicle = vehicleA("78라0004");

        assertThat(statusOf(itemStatusBody(ownerA, vehicle, null), "ENGINE_OIL")).isEqualTo("AHEAD");

        mvc.perform(get("/api/vehicles/" + vehicle + "/item-status").param("mileage", "55000")
                .header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.inputMileage").value(55_000))
            .andExpect(jsonPath("$.data.items[?(@.code=='ENGINE_OIL')].status").value("DUE"))
            .andExpect(jsonPath("$.data.items[?(@.code=='ENGINE_OIL')].dueReason").value("KM"));
        // 주행거리를 비우면 다시 날짜 기준만 (T11)
        assertThat(statusOf(itemStatusBody(ownerA, vehicle, null), "ENGINE_OIL")).isEqualTo("AHEAD");
    }

    @Test
    @DisplayName("기록이 남지 않는다: 주행거리를 넣어 여러 번 불러도 정비 기록 수·차량 최근 주행거리가 그대로다")
    void noRecordsAreWritten() throws Exception {
        long recordsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record", Long.class);
        long vehicle = vehicleA("78라0004");
        Integer mileageBefore = jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicle);

        itemStatusBody(ownerA, vehicle, "55000");
        itemStatusBody(ownerA, vehicle, "2000000");
        itemStatusBody(ownerA, vehicleA("90마0005"), null);
        em.flush();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record", Long.class)).isEqualTo(recordsBefore);
        assertThat(jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicle))
            .isEqualTo(mileageBefore);
    }

    @Test
    @DisplayName("X6: A 토큰으로 B 정비소 차량·없는 차량은 404, B 토큰으로 A 차량도 404, 서로 자기 차량은 200")
    void isolation() throws Exception {
        long vehicleOfB = jdbc.queryForObject("SELECT id FROM vehicle WHERE shop_id = ? ORDER BY id LIMIT 1", Long.class, shopB);
        long vehicleOfA = vehicleA("12가0001");

        mvc.perform(get("/api/vehicles/" + vehicleOfB + "/item-status").header("Authorization", ownerA))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/vehicles/" + vehicleOfA + "/item-status").header("Authorization", ownerB))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/vehicles/999999/item-status").header("Authorization", ownerA))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/vehicles/" + vehicleOfB + "/item-status").header("Authorization", ownerB))
            .andExpect(status().isOk());
        mvc.perform(get("/api/vehicles/" + vehicleOfA + "/item-status").header("Authorization", ownerA))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰 없이 호출하면 401, 정비사 계정(mech_a)도 조회할 수 있다")
    void authentication() throws Exception {
        long vehicle = vehicleA("12가0001");
        mvc.perform(get("/api/vehicles/" + vehicle + "/item-status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/vehicles/" + vehicle + "/item-status").header("Authorization", login("mech_a")))
            .andExpect(status().isOk());
    }

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"1234\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + JsonPath.<String>read(body, "$.data.accessToken");
    }
}
