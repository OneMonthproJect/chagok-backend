package com.wanted.chagok.screen.target;

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
 * 시연 시드({@code db/seed/demo_seed.sql})를 적용한 DB에서 <b>시드 계정으로 로그인해 실제 HTTP로</b> 홈(API 04)을 호출한다
 * (07 7.5, 08 04번, 04 F-HOME-01). 보안 필터·JWT·Controller·Service·실제 reservation·customer·maintenance·item 구현·MySQL이
 * 모두 연결된다. 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 시드는 {@code DemoSeedTest}와 같은
 * 방식으로 테스트 트랜잭션 안에서 실행하고 끝나면 롤백한다.
 *
 * <p>기대값은 07 7.5와 시드 파일의 값이다: 오늘 예약 2건, 오늘 연락할 고객 5명(김민수·박철수·한가람·윤하늘·오세훈. 이영희는 대상이지만
 * "예약 있음"이라 빠진다).
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HomeSeedHttpDbTest {

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

    private String home(String token) throws Exception {
        return mvc.perform(get("/api/home").header("Authorization", token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long vehicleOf(String plate) {
        return jdbc.queryForObject("SELECT id FROM vehicle WHERE shop_id = ? AND plate_number = ?", Long.class, shopA, plate);
    }

    @Test
    @DisplayName("토큰 없이 호출하면 401 AUTH_REQUIRED, 정비사 계정(mech_a)도 조회할 수 있다")
    void authentication() throws Exception {
        mvc.perform(get("/api/home"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));

        mvc.perform(get("/api/home").header("Authorization", login("mech_a")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("7.5 홈 숫자: 오늘 예약 3건(진행중 1건 포함), 오늘 연락할 고객 5명")
    void seedNumbers() throws Exception {
        String body = home(ownerA);

        assertThat(JsonPath.<String>read(body, "$.data.date")).isEqualTo(today.toString());
        assertThat(JsonPath.<Integer>read(body, "$.data.todayReservationCount")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(body, "$.data.todayContactCount")).isEqualTo(5);
    }

    @Test
    @DisplayName("오늘 예약 목록: 90마0005 09:00(진행중)·78라0004 10:00(엔진오일)·22사0007 14:30(기타) 시각 순, 연락처는 전체 번호, 어제·D+2 예약은 나오지 않고 담당(assignee)이 실린다")
    void todayReservations() throws Exception {
        mvc.perform(get("/api/home").header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.todayReservations.length()").value(3))
            .andExpect(jsonPath("$.data.todayReservations[0].plateNumber").value("90마0005"))
            .andExpect(jsonPath("$.data.todayReservations[0].reservedAt").value(today + "T09:00:00"))
            .andExpect(jsonPath("$.data.todayReservations[0].status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.todayReservations[0].customerName").value("정우성"))
            .andExpect(jsonPath("$.data.todayReservations[0].version").value(0))
            .andExpect(jsonPath("$.data.todayReservations[0].phone").value("010-****-0005"))
            .andExpect(jsonPath("$.data.todayReservations[0].purposeSummary").value("엔진오일"))
            .andExpect(jsonPath("$.data.todayReservations[0].assignee.name").value("김정비"))
            .andExpect(jsonPath("$.data.todayReservations[1].plateNumber").value("78라0004"))
            .andExpect(jsonPath("$.data.todayReservations[1].reservedAt").value(today + "T10:00:00"))
            .andExpect(jsonPath("$.data.todayReservations[1].status").value("RESERVED"))
            .andExpect(jsonPath("$.data.todayReservations[1].version").value(0))
            .andExpect(jsonPath("$.data.todayReservations[1].customerName").value("최지은"))
            .andExpect(jsonPath("$.data.todayReservations[1].phone").value("010-****-0004"))
            .andExpect(jsonPath("$.data.todayReservations[1].purposeSummary").value("엔진오일"))
            .andExpect(jsonPath("$.data.todayReservations[1].assignee.name").value("김정비"))
            .andExpect(jsonPath("$.data.todayReservations[1].assignee.colorIndex").value(1))
            .andExpect(jsonPath("$.data.todayReservations[2].plateNumber").value("22사0007"))
            .andExpect(jsonPath("$.data.todayReservations[2].reservedAt").value(today + "T14:30:00"))
            .andExpect(jsonPath("$.data.todayReservations[2].purposeSummary").value("기타"))
            .andExpect(jsonPath("$.data.todayReservations[2].assignee.name").value("박사장"))
            .andExpect(jsonPath("$.data.todayReservations[2].assignee.colorIndex").value(0))
            .andExpect(jsonPath("$.data.todayReservations[?(@.plateNumber=='55차0010')]").isEmpty())
            .andExpect(jsonPath("$.data.todayReservations[?(@.plateNumber=='34나0002')]").isEmpty());
    }

    @Test
    @DisplayName("홈 숫자는 S-04 대상 목록과 같은 기준이다: 연락할 고객 수 = 예약 없는 대상 차량이 있는 고객 수 (이영희는 대상이지만 예약 있음이라 뺀다)")
    void contactCountMatchesTheTargetList() throws Exception {
        String home = home(ownerA);
        String targets = mvc.perform(get("/api/targets").header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Integer> customersWithoutReservation = JsonPath.read(targets,
            "$.data.items[?(@.activeReservation == null)].customerId");
        assertThat(JsonPath.<List<?>>read(targets, "$.data.items")).hasSize(6);
        assertThat(customersWithoutReservation).hasSize(5);
        assertThat(JsonPath.<Integer>read(home, "$.data.todayContactCount"))
            .isEqualTo(customersWithoutReservation.stream().distinct().count());
    }

    @Test
    @DisplayName("04 완료 기준: 대상 고객(김민수 12가0001)의 예약을 하나 등록하면 오늘 연락할 고객에서 빠지고(5→4), 오늘 예약이 4건(시각 순)이 된다")
    void registeringAReservationRemovesTheCustomerFromContacts() throws Exception {
        mvc.perform(post("/api/reservations").header("Authorization", ownerA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleId\":" + vehicleOf("12가0001") + ",\"reservedAt\":\"" + today
                    + "T18:00:00\",\"purpose\":{\"itemIds\":[],\"other\":false,\"memo\":null},\"assigneeId\":"
                    + staffIdByToken.get(ownerA) + "}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/home").header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.todayContactCount").value(4))
            .andExpect(jsonPath("$.data.todayReservationCount").value(4))
            .andExpect(jsonPath("$.data.todayReservations.length()").value(4))
            .andExpect(jsonPath("$.data.todayReservations[3].plateNumber").value("12가0001"))
            .andExpect(jsonPath("$.data.todayReservations[3].reservedAt").value(today + "T18:00:00"));
    }

    @Test
    @DisplayName("격리: B 정비소 홈은 오늘 예약 0건·빈 목록이고 연락할 고객은 B의 강동원 1명뿐이며, A 정비소 예약·고객이 섞이지 않는다")
    void isolation() throws Exception {
        mvc.perform(get("/api/home").header("Authorization", ownerB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.todayReservationCount").value(0))
            .andExpect(jsonPath("$.data.todayReservations.length()").value(0))
            .andExpect(jsonPath("$.data.todayContactCount").value(1));
    }

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"1234\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = "Bearer " + JsonPath.<String>read(body, "$.data.accessToken");
        staffIdByToken.put(token, ((Number) JsonPath.read(body, "$.data.staff.staffId")).longValue());
        return token;
    }

    /** 로그인한 계정의 staffId(토큰별). P0-나머지부터 예약 등록은 담당 {@code assigneeId}가 필수라 자기 자신을 담당으로 보낸다. */
    private final java.util.Map<String, Long> staffIdByToken = new java.util.HashMap<>();
}
