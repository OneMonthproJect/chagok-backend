package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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
 * 시연 시드({@code db/seed/demo_seed.sql})를 적용한 DB에서 <b>시드 계정으로 로그인해 실제 HTTP로</b> 차량 추가(API 08)·차량 상세(API 09)를
 * 호출한다 (07 7.3, 08 08·09번, X2·X8·X17). 보안 필터·JWT·Controller·Service·MySQL이 모두 연결된다.
 * 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 시드는 {@code DemoSeedTest}와 같은 방식으로
 * 테스트 트랜잭션 안에서 실행하고 끝나면 롤백한다. 이 API들은 "오늘"에 의존하지 않아 시드 날짜 가드가 필요 없다.
 *
 * <p>기대값은 시드 파일의 값이다: A 정비소 고객 10명·차량 11대, B 정비소 고객 1명·차량 1대. 차량번호 {@code 12가0001}은 두 정비소에 모두 있다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VehicleSeedHttpDbTest {

    private static final FileSystemResource SEED = new FileSystemResource("db/seed/demo_seed.sql");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private String ownerA;
    private String ownerB;
    private long shopA;
    private long shopB;

    @BeforeEach
    void applySeedAndLogin() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        ScriptUtils.executeSqlScript(connection, new EncodedResource(SEED, StandardCharsets.UTF_8));

        shopA = jdbc.queryForObject("SELECT id FROM shop WHERE name = '차곡카센터'", Long.class);
        shopB = jdbc.queryForObject("SELECT id FROM shop WHERE name = '바른카센터'", Long.class);
        ownerA = login("owner_a");
        ownerB = login("owner_b");
    }

    private long vehicleId(long shopId, String plate) {
        return jdbc.queryForObject("SELECT id FROM vehicle WHERE shop_id = ? AND plate_number = ?", Long.class, shopId,
            plate);
    }

    private long customerId(long shopId, String name) {
        return jdbc.queryForObject("SELECT id FROM customer WHERE shop_id = ? AND name = ?", Long.class, shopId, name);
    }

    private int vehicleCount(long shopId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE shop_id = ?", Integer.class, shopId);
    }

    // ------------------------------------------------------------------ 인증

    @Test
    @DisplayName("토큰 없이 08·09를 호출하면 401 AUTH_REQUIRED")
    void authentication() throws Exception {
        mvc.perform(get("/api/vehicles/" + vehicleId(shopA, "78라0004")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        mvc.perform(post("/api/customers/" + customerId(shopA, "최지은") + "/vehicles")
                .contentType(MediaType.APPLICATION_JSON).content("{\"plateNumber\":\"88허0012\",\"model\":\"K3\"}"))
            .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 차량 상세 (API 09)

    @Test
    @DisplayName("09 시드 차량 78라0004: K5·2020·45000km, 고객 최지은(전체 연락처, consent true)")
    void detailOfSeedVehicle() throws Exception {
        long vehicle = vehicleId(shopA, "78라0004");

        mvc.perform(get("/api/vehicles/" + vehicle).header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.vehicleId").value(vehicle))
            .andExpect(jsonPath("$.data.plateNumber").value("78라0004"))
            .andExpect(jsonPath("$.data.model").value("K5"))
            .andExpect(jsonPath("$.data.modelYear").value(2020))
            .andExpect(jsonPath("$.data.latestMileage").value(45000))
            .andExpect(jsonPath("$.data.customer.customerId").value(customerId(shopA, "최지은")))
            .andExpect(jsonPath("$.data.customer.name").value("최지은"))
            .andExpect(jsonPath("$.data.customer.phone").value("010-0000-0004"))
            .andExpect(jsonPath("$.data.customer.consent").value(true));
    }

    @Test
    @DisplayName("09 연식·주행거리가 비어 있는 시드 차량 66카0011은 두 값이 null이고, 미동의 고객(장미래)의 차량은 consent false")
    void nullableFieldsAndConsentFalse() throws Exception {
        mvc.perform(get("/api/vehicles/" + vehicleId(shopA, "66카0011")).header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.model").value("K3"))
            .andExpect(jsonPath("$.data.modelYear").value(nullValue()))
            .andExpect(jsonPath("$.data.latestMileage").value(nullValue()))
            .andExpect(jsonPath("$.data.customer.name").value("오세훈"));

        mvc.perform(get("/api/vehicles/" + vehicleId(shopA, "44자0009")).header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.customer.name").value("장미래"))
            .andExpect(jsonPath("$.data.customer.consent").value(false));
    }

    @Test
    @DisplayName("X2 09 B 정비소 차량을 A 토큰으로 조회하면 404 NOT_FOUND(없는 id와 같은 응답), B 토큰으로는 B 고객이 보인다")
    void detailIsolation() throws Exception {
        long bVehicle = vehicleId(shopB, "12가0001");

        mvc.perform(get("/api/vehicles/" + bVehicle).header("Authorization", ownerA))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/vehicles/999999999").header("Authorization", ownerA))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mvc.perform(get("/api/vehicles/" + bVehicle).header("Authorization", ownerB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.customer.name").value("강동원"));
        // 같은 차량번호(12가0001)가 A 정비소에도 있지만 서로 다른 차량이다
        assertThat(vehicleId(shopA, "12가0001")).isNotEqualTo(bVehicle);
    }

    // ------------------------------------------------------------------ 차량 추가 (API 08)

    @Test
    @DisplayName("08 시드 고객 최지은에게 차량 추가: 공백은 제거되어 저장되고, 응답 id로 09를 호출하면 같은 고객의 두 번째 차량이 보인다")
    void addVehicleToSeedCustomer() throws Exception {
        long customer = customerId(shopA, "최지은");

        String body = mvc.perform(post("/api/customers/" + customer + "/vehicles").header("Authorization", ownerA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plateNumber\":\"88 허 0012\",\"model\":\"K3\",\"modelYear\":2022,\"mileage\":1000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.customerId").value(customer))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long added = JsonPath.<Number>read(body, "$.data.vehicleId").longValue();

        mvc.perform(get("/api/vehicles/" + added).header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plateNumber").value("88허0012"))
            .andExpect(jsonPath("$.data.modelYear").value(2022))
            .andExpect(jsonPath("$.data.latestMileage").value(1000))
            .andExpect(jsonPath("$.data.customer.name").value("최지은"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE customer_id = ?", Integer.class, customer))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT shop_id FROM vehicle WHERE id = ?", Long.class, added)).isEqualTo(shopA);
    }

    @Test
    @DisplayName("X8 08 본문에 shopId를 B 정비소로 넣어도 읽지 않아 토큰의 정비소(A)에 저장된다")
    void bodyShopIdIsIgnored() throws Exception {
        long customer = customerId(shopA, "최지은");

        String body = mvc.perform(post("/api/customers/" + customer + "/vehicles").header("Authorization", ownerA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plateNumber\":\"77하0013\",\"model\":\"K3\",\"shopId\":" + shopB + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long added = JsonPath.<Number>read(body, "$.data.vehicleId").longValue();

        assertThat(jdbc.queryForObject("SELECT shop_id FROM vehicle WHERE id = ?", Long.class, added)).isEqualTo(shopA);
    }

    @Test
    @DisplayName("X17 08 B 정비소 고객에게 A 토큰으로 추가하면 404이고 차량은 저장되지 않는다(없는 고객과 같은 응답), 연식 초과는 400")
    void addVehicleIsolationAndValidation() throws Exception {
        long bCustomer = customerId(shopB, "강동원");
        int bBefore = vehicleCount(shopB);
        int aBefore = vehicleCount(shopA);

        mvc.perform(post("/api/customers/" + bCustomer + "/vehicles").header("Authorization", ownerA)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plateNumber\":\"88허0012\",\"model\":\"K3\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(post("/api/customers/999999999/vehicles").header("Authorization", ownerA)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plateNumber\":\"88허0012\",\"model\":\"K3\"}"))
            .andExpect(status().isNotFound());
        assertThat(vehicleCount(shopB)).isEqualTo(bBefore);

        mvc.perform(post("/api/customers/" + customerId(shopA, "최지은") + "/vehicles").header("Authorization", ownerA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plateNumber\":\"88허0012\",\"model\":\"K3\",\"modelYear\":2999}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("modelYear"));
        assertThat(vehicleCount(shopA)).isEqualTo(aBefore);
    }

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"1234\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + JsonPath.<String>read(body, "$.data.accessToken");
    }
}
