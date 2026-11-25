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
import java.util.List;
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
 * 시연 시드({@code db/seed/demo_seed.sql})를 적용한 DB에서 <b>시드 계정으로 로그인해 실제 HTTP로</b> 고객 상세(API 07)를 호출한다
 * (08 07번 예시, 07 7.3·7.5, D-63 B01, 격리 X1). 보안 필터·JWT·Controller·Service·실제 customer·item·maintenance 구현·MySQL이 연결된다.
 * 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 시드는 {@code ReservationSeedHttpDbTest}와 같은 방식으로
 * 테스트 트랜잭션 안에서 실행하고 끝나면 롤백한다. 시드의 "오늘"과 앱의 "오늘"은 모두 서울 날짜다(chagok-docs#110).
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerDetailSeedHttpDbTest {

    private static final FileSystemResource SEED = new FileSystemResource("db/seed/demo_seed.sql");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

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

    private long customerId(long shopId, String name) {
        return jdbc.queryForObject("SELECT id FROM customer WHERE shop_id = ? AND name = ?", Long.class, shopId, name);
    }

    @Test
    @DisplayName("08 07번 예시: 오세훈 — 55차0010은 도래 0·임박 1(배터리), 66카0011은 기록이 없어 0·0, 연락처는 전체 번호")
    void spec07Example() throws Exception {
        mvc.perform(get("/api/customers/" + customerId(shopA, "오세훈")).header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("오세훈"))
            .andExpect(jsonPath("$.data.phone").value("010-0000-0010"))
            .andExpect(jsonPath("$.data.consent").value(true))
            .andExpect(jsonPath("$.data.vehicles.length()").value(2))
            .andExpect(jsonPath("$.data.vehicles[0].plateNumber").value("55차0010"))
            .andExpect(jsonPath("$.data.vehicles[0].model").value("쏘나타"))
            .andExpect(jsonPath("$.data.vehicles[0].modelYear").value(2018))
            .andExpect(jsonPath("$.data.vehicles[0].latestMileage").value(97_000))
            .andExpect(jsonPath("$.data.vehicles[0].dueCount").value(0))
            .andExpect(jsonPath("$.data.vehicles[0].soonCount").value(1))
            .andExpect(jsonPath("$.data.vehicles[1].plateNumber").value("66카0011"))
            .andExpect(jsonPath("$.data.vehicles[1].model").value("K3"))
            .andExpect(jsonPath("$.data.vehicles[1].modelYear").isEmpty())
            .andExpect(jsonPath("$.data.vehicles[1].latestMileage").isEmpty())
            .andExpect(jsonPath("$.data.vehicles[1].dueCount").value(0))
            .andExpect(jsonPath("$.data.vehicles[1].soonCount").value(0));
    }

    @Test
    @DisplayName("A 정비소 고객 10명·차량 11대 모두에서 07의 dueCount·soonCount가 API 10(주행거리 없이)의 DUE·SOON 수와 같다")
    void countsMatchApi10ForEverySeedVehicle() throws Exception {
        List<Long> customers = jdbc.queryForList("SELECT id FROM customer WHERE shop_id = ? ORDER BY id", Long.class, shopA);
        assertThat(customers).hasSize(10);

        int vehicles = 0;
        int withDueOrSoon = 0;
        for (long customer : customers) {
            String detail = mvc.perform(get("/api/customers/" + customer).header("Authorization", ownerA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            List<Integer> vehicleIds = JsonPath.read(detail, "$.data.vehicles[*].vehicleId");
            for (int i = 0; i < vehicleIds.size(); i++) {
                vehicles++;
                String itemStatus = mvc.perform(get("/api/vehicles/" + vehicleIds.get(i) + "/item-status")
                        .header("Authorization", ownerA))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                List<String> statuses = JsonPath.read(itemStatus, "$.data.items[*].status");
                int due = (int) statuses.stream().filter("DUE"::equals).count();
                int soon = (int) statuses.stream().filter("SOON"::equals).count();
                assertThat((Integer) JsonPath.read(detail, "$.data.vehicles[" + i + "].dueCount"))
                    .as("dueCount 차량 " + vehicleIds.get(i)).isEqualTo(due);
                assertThat((Integer) JsonPath.read(detail, "$.data.vehicles[" + i + "].soonCount"))
                    .as("soonCount 차량 " + vehicleIds.get(i)).isEqualTo(soon);
                if (due + soon > 0) {
                    withDueOrSoon++;
                }
            }
        }
        assertThat(vehicles).isEqualTo(11);
        // 시드가 모든 숫자가 0인 채로 우연히 일치하지 않도록: 도래·임박이 있는 차량이 실제로 있어야 한다
        assertThat(withDueOrSoon).isGreaterThan(0);
    }

    @Test
    @DisplayName("X1: A 토큰으로 B 고객은 404, B 토큰으로 A 고객도 404, 없는 고객도 404")
    void isolation() throws Exception {
        long customerOfA = customerId(shopA, "오세훈");
        long customerOfB = jdbc.queryForObject("SELECT id FROM customer WHERE shop_id = ? ORDER BY id LIMIT 1", Long.class, shopB);

        mvc.perform(get("/api/customers/" + customerOfB).header("Authorization", ownerA))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/customers/" + customerOfA).header("Authorization", ownerB))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/customers/999999").header("Authorization", ownerA))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/customers/" + customerOfB).header("Authorization", ownerB))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰 없이 호출하면 401, 정비사 계정(mech_a)도 조회할 수 있다")
    void authentication() throws Exception {
        long customer = customerId(shopA, "오세훈");
        mvc.perform(get("/api/customers/" + customer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers/" + customer).header("Authorization", login("mech_a")))
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
