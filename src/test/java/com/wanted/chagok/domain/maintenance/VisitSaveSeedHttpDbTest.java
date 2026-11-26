package com.wanted.chagok.domain.maintenance;

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
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시연 시드({@code db/seed/demo_seed.sql})를 적용한 DB에서 <b>시드 계정으로 로그인해 실제 HTTP로</b> 방문·정비 기록 저장(API 12)을 호출하고,
 * 저장 결과가 이력(API 11)과 항목 상태(API 10)에 나타나는지 본다 (04 F-MNT-01·T12, 08 12, D-58, 격리 X5·X11). 이슈 #42 완료 정의의
 * 「시드 데이터 확인」을 채운다. 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 시드는
 * {@code ReservationSeedHttpDbTest}와 같은 방식으로 테스트 트랜잭션 안에서 실행하고 끝나면 롤백한다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VisitSaveSeedHttpDbTest {

    private static final FileSystemResource SEED = new FileSystemResource("db/seed/demo_seed.sql");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private jakarta.persistence.EntityManager em;

    private JdbcTemplate jdbc;
    private LocalDate today;
    private long shopA;
    private long shopB;
    private String ownerA;
    private String mechA;
    private String ownerB;

    @BeforeEach
    void applySeedAndLogin() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        ScriptUtils.executeSqlScript(connection, new EncodedResource(SEED, StandardCharsets.UTF_8));

        LocalDate seedToday = LocalDate.parse(jdbc.queryForObject("SELECT DATE_FORMAT(@today, '%Y-%m-%d')", String.class));
        today = LocalDate.now(ClockConfig.SEOUL);
        assertThat(seedToday).as("시드의 오늘(@today)과 앱의 오늘(Asia/Seoul)").isEqualTo(today);

        shopA = jdbc.queryForObject("SELECT id FROM shop WHERE name = '차곡카센터'", Long.class);
        shopB = jdbc.queryForObject("SELECT id FROM shop WHERE name = '바른카센터'", Long.class);
        ownerA = login("owner_a");
        mechA = login("mech_a");
        ownerB = login("owner_b");
    }

    private long vehicleA(String plate) {
        return jdbc.queryForObject("SELECT id FROM vehicle WHERE shop_id = ? AND plate_number = ?", Long.class, shopA, plate);
    }

    private long itemA(String code) {
        return jdbc.queryForObject("SELECT id FROM service_item WHERE shop_id = ? AND code = ?", Long.class, shopA, code);
    }

    /** 같은 트랜잭션이라 JPA 변경(차량 UPDATE)을 JDBC로 읽기 전에 flush한다. */
    private Integer latestMileage(long vehicleId) {
        em.flush();
        return jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicleId);
    }

    private java.util.Map<String, Object> vehicleRow(long vehicleId) {
        em.flush();
        return jdbc.queryForMap("SELECT model_year, latest_mileage FROM vehicle WHERE id = ?", vehicleId);
    }

    private long recordCount(long vehicleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record WHERE vehicle_id = ?", Long.class, vehicleId);
    }

    private ResultActions save(String token, long vehicleId, String body) throws Exception {
        return mvc.perform(post("/api/vehicles/" + vehicleId + "/visits").header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String itemStatus(String token, long vehicleId, String code) throws Exception {
        String body = mvc.perform(get("/api/vehicles/" + vehicleId + "/item-status").header("Authorization", token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> found = JsonPath.read(body, "$.data.items[?(@.code=='" + code + "')].status");
        return found.get(0);
    }

    @Test
    @DisplayName("12가0001: 정비사(mech_a)가 엔진오일 교체·타이어 점검을 저장하면 이력에 한 묶음으로 나오고, 엔진오일은 도래에서 여유로 바뀌며 점검은 상태를 바꾸지 않는다 (T12)")
    void saveShowsInHistoryAndResetsCycle() throws Exception {
        long vehicle = vehicleA("12가0001");
        long oil = itemA("ENGINE_OIL");
        long tire = itemA("TIRE");
        String tireBefore = itemStatus(mechA, vehicle, "TIRE");
        assertThat(itemStatus(mechA, vehicle, "ENGINE_OIL")).isEqualTo("DUE");
        long recordsBefore = recordCount(vehicle);

        String saved = save(mechA, vehicle, """
            {"visitDate": "%s", "mileage": 55000,
             "records": [{"itemId": %d, "type": "REPLACE", "memo": "교체"},
                         {"itemId": %d, "type": "INSPECT", "memo": "마모 확인"}]}
            """.formatted(today, oil, tire))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.vehicleId").value(vehicle))
            .andExpect(jsonPath("$.data.savedCount").value(2))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String visitKey = JsonPath.read(saved, "$.data.visitKey");
        assertThat(UUID.fromString(visitKey)).isNotNull();
        assertThat(recordCount(vehicle)).isEqualTo(recordsBefore + 2);

        // API 11: 가장 최근 방문이 방금 저장한 묶음이다(오늘 날짜, 담당은 저장한 계정, 항목 이름 포함)
        mvc.perform(get("/api/vehicles/" + vehicle + "/visits").header("Authorization", mechA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].visitKey").value(visitKey))
            .andExpect(jsonPath("$.data.items[0].visitDate").value(today.toString()))
            .andExpect(jsonPath("$.data.items[0].mileage").value(55_000))
            .andExpect(jsonPath("$.data.items[0].staffName").value("김정비"))
            .andExpect(jsonPath("$.data.items[0].records.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].records[?(@.type=='REPLACE')].itemName").value("엔진오일"))
            .andExpect(jsonPath("$.data.items[0].records[?(@.type=='INSPECT')].memo").value("마모 확인"));

        // API 10: 교체한 엔진오일은 여유, 점검만 한 타이어는 그대로 (T12)
        assertThat(itemStatus(mechA, vehicle, "ENGINE_OIL")).isEqualTo("AHEAD");
        assertThat(itemStatus(mechA, vehicle, "TIRE")).isEqualTo(tireBefore);
    }

    @Test
    @DisplayName("차량의 최근 주행거리는 더 클 때만 갱신된다: 12가0001(40,000)에 55,000을 저장하면 55,000, 이어서 10,000을 저장해도 55,000")
    void latestMileageOnlyIncreases() throws Exception {
        long vehicle = vehicleA("12가0001");
        long oil = itemA("ENGINE_OIL");
        assertThat(latestMileage(vehicle))
            .isEqualTo(40_000);

        save(ownerA, vehicle, body(oil, 55_000, null)).andExpect(status().isOk());
        assertThat(latestMileage(vehicle))
            .isEqualTo(55_000);

        save(ownerA, vehicle, body(oil, 10_000, null)).andExpect(status().isOk());
        assertThat(latestMileage(vehicle))
            .as("작은 값은 기록만 저장하고 차량은 갱신하지 않는다").isEqualTo(55_000);
        assertThat(jdbc.queryForObject("SELECT mileage FROM maintenance_record WHERE vehicle_id = ? ORDER BY id DESC LIMIT 1",
            Integer.class, vehicle)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("D-58: 연식·주행거리가 비어 있는 66카0011은 값 없이 저장하면 400(두 필드)이고 아무것도 남지 않으며, 값을 보내면 저장되고 차량에 반영된다")
    void emptyVehicleNeedsYearAndMileage() throws Exception {
        long vehicle = vehicleA("66카0011");
        long oil = itemA("ENGINE_OIL");
        long recordsBefore = recordCount(vehicle);

        save(ownerA, vehicle, body(oil, null, null))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[?(@.field=='vehicle.modelYear')]").isNotEmpty())
            .andExpect(jsonPath("$.error.fieldErrors[?(@.field=='mileage')]").isNotEmpty());
        assertThat(recordCount(vehicle)).isEqualTo(recordsBefore);

        save(ownerA, vehicle, body(oil, 30_000, 2020)).andExpect(status().isOk());
        assertThat(recordCount(vehicle)).isEqualTo(recordsBefore + 1);
        assertThat(vehicleRow(vehicle))
            .containsEntry("model_year", 2020).containsEntry("latest_mileage", 30_000);
    }

    @Test
    @DisplayName("X5: A 토큰으로 B 정비소 차량·없는 차량은 404, B 토큰으로 A 차량도 404이고 어느 쪽 기록·차량 값도 바뀌지 않는다")
    void isolationOfVehicle() throws Exception {
        long vehicleOfB = jdbc.queryForObject("SELECT id FROM vehicle WHERE shop_id = ? ORDER BY id LIMIT 1", Long.class, shopB);
        long vehicleOfA = vehicleA("12가0001");
        long oilA = itemA("ENGINE_OIL");
        long recordsOfB = recordCount(vehicleOfB);
        long recordsOfA = recordCount(vehicleOfA);

        save(ownerA, vehicleOfB, body(oilA, 99_000, 2020)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        save(ownerA, 999_999L, body(oilA, 99_000, 2020)).andExpect(status().isNotFound());
        save(ownerB, vehicleOfA, body(oilA, 99_000, 2020)).andExpect(status().isNotFound());

        assertThat(recordCount(vehicleOfB)).isEqualTo(recordsOfB);
        assertThat(recordCount(vehicleOfA)).isEqualTo(recordsOfA);
        assertThat(latestMileage(vehicleOfA))
            .isEqualTo(40_000);
        assertThat(vehicleRow(vehicleOfB))
            .containsEntry("model_year", null).containsEntry("latest_mileage", null);
    }

    @Test
    @DisplayName("X11: A 토큰으로 B 정비소의 항목 id를 보내면 400이고 같은 요청의 다른 항목도 저장되지 않는다")
    void itemOfOtherShop() throws Exception {
        long vehicle = vehicleA("12가0001");
        long oilA = itemA("ENGINE_OIL");
        long itemOfB = jdbc.queryForObject("SELECT id FROM service_item WHERE shop_id = ? ORDER BY id LIMIT 1", Long.class, shopB);
        long recordsBefore = recordCount(vehicle);

        save(ownerA, vehicle, """
            {"visitDate": "%s", "mileage": 55000,
             "records": [{"itemId": %d, "type": "REPLACE"}, {"itemId": %d, "type": "INSPECT"}]}
            """.formatted(today, oilA, itemOfB))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        assertThat(recordCount(vehicle)).isEqualTo(recordsBefore);
        assertThat(latestMileage(vehicle))
            .isEqualTo(40_000);
    }

    @Test
    @DisplayName("없는 담당 정비사(X13)·정비일 없는 외부 기록·미래 방문일은 400이고, 토큰 없으면 401")
    void notOpenInputsAndAuth() throws Exception {
        long vehicle = vehicleA("12가0001");
        long oil = itemA("ENGINE_OIL");
        long recordsBefore = recordCount(vehicle);

        save(ownerA, vehicle, """
            {"visitDate": "%s", "mileage": 55000, "mechanicId": 999999,
             "records": [{"itemId": %d, "type": "EXTERNAL", "externalDate": "%s"}]}
            """.formatted(today, oil, today.minusDays(5)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("mechanicId"));
        save(ownerA, vehicle, """
            {"visitDate": "%s", "mileage": 55000, "records": [{"itemId": %d, "type": "EXTERNAL"}]}
            """.formatted(today, oil))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("records[0].externalDate"));
        save(ownerA, vehicle, """
            {"visitDate": "%s", "mileage": 55000, "records": [{"itemId": %d, "type": "REPLACE"}]}
            """.formatted(today.plusDays(1), oil))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("visitDate"));
        mvc.perform(post("/api/vehicles/" + vehicle + "/visits").contentType(MediaType.APPLICATION_JSON)
                .content(body(oil, 55_000, null)))
            .andExpect(status().isUnauthorized());

        assertThat(recordCount(vehicle)).isEqualTo(recordsBefore);
    }

    private String body(long itemId, Integer mileage, Integer modelYear) {
        return "{\"visitDate\": \"" + today + "\""
            + (mileage == null ? "" : ", \"mileage\": " + mileage)
            + (modelYear == null ? "" : ", \"vehicle\": {\"modelYear\": " + modelYear + "}")
            + ", \"records\": [{\"itemId\": " + itemId + ", \"type\": \"REPLACE\"}]}";
    }

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"1234\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + JsonPath.<String>read(body, "$.data.accessToken");
    }
}
