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
import java.util.Map;
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
 * 시연 시드({@code db/seed/demo_seed.sql})를 적용한 DB에서 <b>시드 계정으로 로그인해 실제 HTTP로</b> 재점검 대상 목록(API 14)을 호출한다
 * (07 7.2·7.5, 08 14번, 04 F-TGT-01, 시나리오 A). 보안 필터·JWT·Controller·Service·실제 customer·maintenance·item·reservation 구현·
 * MySQL이 모두 연결된다. 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 시드는 {@code DemoSeedTest}와
 * 같은 방식으로 테스트 트랜잭션 안에서 실행하고 끝나면 롤백한다.
 *
 * <p>기대값은 07 7.5와 시드 파일의 값이다: 대상 6대(도래 3대 + 임박 3대), 미동의 고객과 여유·미확인 차량은 나오지 않는다. 날짜는 시드의
 * 오늘(서울 날짜) 기준 상대값이라 날짜를 고정하지 않고 관계(도래 = 다음 정비일이 오늘 이전, 임박 = 한 달 안)를 확인한다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TargetSeedHttpDbTest {

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

    private String targets(String token) throws Exception {
        return mvc.perform(get("/api/targets").header("Authorization", token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> plates(String body) {
        return JsonPath.read(body, "$.data.items[*].plateNumber");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(String body, String plate) {
        List<Map<String, Object>> rows = JsonPath.read(body, "$.data.items[?(@.plateNumber=='" + plate + "')]");
        assertThat(rows).as(plate + " 줄").hasSize(1);
        return rows.get(0);
    }

    private static LocalDate earliestDueDate(String body, String plate) {
        List<String> dates = JsonPath.read(body, "$.data.items[?(@.plateNumber=='" + plate + "')].targetItems[*].nextDueDate");
        return dates.stream().map(LocalDate::parse).min(LocalDate::compareTo).orElseThrow();
    }

    private long reservationOf(String plate) {
        return jdbc.queryForObject("""
            SELECT r.id FROM reservation r JOIN vehicle v ON v.id = r.vehicle_id
            WHERE r.shop_id = ? AND v.plate_number = ?
            """, Long.class, shopA, plate);
    }

    @Test
    @DisplayName("토큰 없이 호출하면 401 AUTH_REQUIRED, 정비사 계정(mech_a)도 조회할 수 있다")
    void authentication() throws Exception {
        mvc.perform(get("/api/targets"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));

        mvc.perform(get("/api/targets").header("Authorization", login("mech_a")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("7.5 대상 6대: 도래 3대(한가람 11바0006 → 이영희 34나0002 → 김민수 12가0001)가 먼저, 임박 3대(박철수·윤하늘·오세훈)가 그다음")
    void sixTargetsInOrder() throws Exception {
        String body = targets(ownerA);

        assertThat(JsonPath.<String>read(body, "$.data.asOf")).isEqualTo(today.toString());
        List<String> plates = plates(body);
        assertThat(plates).hasSize(6);
        // 도래는 다음 정비일이 이른 순: 11바0006(D-8M+6M = 두 달 전) → 34나0002(10일 전) → 12가0001(7일 전)
        assertThat(plates.subList(0, 3)).containsExactly("11바0006", "34나0002", "12가0001");
        // 임박 3대는 다음 정비일이 같거나 비슷해 이 사이의 순서는 날짜·차량 id에 따른다 — 집합만 확인한다
        assertThat(plates.subList(3, 6)).containsExactlyInAnyOrder("56다0003", "33아0008", "55차0010");
        // 임박 구간에서도 가장 이른 다음 정비일이 비내림차순이다(정렬 증명. 날짜가 같으면 차량 id 순이라 집합으로만 고정하지 않는다)
        List<LocalDate> soonDates = plates.subList(3, 6).stream().map(p -> earliestDueDate(body, p)).toList();
        assertThat(soonDates).isSorted();
    }

    @Test
    @DisplayName("08 14 꼬리표(07 7.2): 11바0006 엔진오일 targetItem에 lastDecline(D-1M), 33아0008 타이어에 lastCaution(D-2M, 메모)이 붙고 서로 다른 값은 null이다")
    @SuppressWarnings("unchecked")
    void seedNotesAreAttachedToTargetItems() throws Exception {
        String body = targets(ownerA);

        List<Map<String, Object>> oil = JsonPath.read(body, "$.data.items[?(@.plateNumber=='11바0006')].targetItems[0]");
        assertThat(oil.get(0).get("name")).isEqualTo("엔진오일");
        assertThat(oil.get(0).get("status")).isEqualTo("DUE"); // 거절은 상태를 바꾸지 않는다
        Map<String, Object> decline = (Map<String, Object>) oil.get(0).get("lastDecline");
        assertThat(decline.get("date")).isEqualTo(today.minusMonths(1).toString());
        assertThat(decline.get("memo")).isNull();
        assertThat(oil.get(0).get("lastCaution")).isNull();

        List<Map<String, Object>> tire = JsonPath.read(body, "$.data.items[?(@.plateNumber=='33아0008')].targetItems[0]");
        assertThat(tire.get(0).get("name")).isEqualTo("타이어 상태 점검");
        assertThat(tire.get(0).get("status")).isEqualTo("SOON"); // 점검·주의는 상태를 바꾸지 않는다
        Map<String, Object> caution = (Map<String, Object>) tire.get(0).get("lastCaution");
        assertThat(caution.get("date")).isEqualTo(today.minusMonths(2).toString());
        assertThat(caution.get("memo")).isEqualTo("타이어 트레드 마모 진행, 다음 방문 때 교체 권장");
        assertThat(tire.get(0).get("lastDecline")).isNull();

        // 꼬리표가 없는 대상 항목은 둘 다 null
        List<Map<String, Object>> plain = JsonPath.read(body, "$.data.items[?(@.plateNumber=='12가0001')].targetItems[0]");
        assertThat(plain.get(0).get("lastDecline")).isNull();
        assertThat(plain.get(0).get("lastCaution")).isNull();
    }

    @Test
    @DisplayName("대상이 아닌 차량: 미동의(장미래 44자0009), 여유(최지은 78라0004·서민준 22사0007), 미확인(정우성 90마0005·66카0011)은 나오지 않는다")
    void nonTargetsAreExcluded() throws Exception {
        List<String> plates = plates(targets(ownerA));

        assertThat(plates).doesNotContain("44자0009", "78라0004", "22사0007", "90마0005", "66카0011");
    }

    @Test
    @DisplayName("줄 구성: 고객·전체 연락처·차량, 도래 항목은 다음 정비일이 오늘 이전, 임박 항목은 한 달 안, baseDate + 주기 = 다음 정비일")
    void rowDetails() throws Exception {
        String body = targets(ownerA);

        Map<String, Object> due = row(body, "12가0001");
        assertThat(due.get("customerName")).isEqualTo("김민수");
        assertThat(due.get("phone")).isEqualTo("010-****-0001");
        List<Map<String, Object>> dueItems = JsonPath.read(body, "$.data.items[?(@.plateNumber=='12가0001')].targetItems[0]");
        assertThat(dueItems).hasSize(1);
        assertThat(dueItems.get(0).get("name")).isEqualTo("엔진오일");
        assertThat(dueItems.get(0).get("status")).isEqualTo("DUE");
        LocalDate dueDate = LocalDate.parse((String) dueItems.get(0).get("nextDueDate"));
        LocalDate baseDate = LocalDate.parse((String) dueItems.get(0).get("baseDate"));
        assertThat(dueDate).isEqualTo(baseDate.plusMonths(6)).isBeforeOrEqualTo(today);

        List<Map<String, Object>> soonItems = JsonPath.read(body, "$.data.items[?(@.plateNumber=='56다0003')].targetItems[0]");
        assertThat(soonItems.get(0).get("name")).isEqualTo("브레이크패드");
        assertThat(soonItems.get(0).get("status")).isEqualTo("SOON");
        LocalDate soonDate = LocalDate.parse((String) soonItems.get(0).get("nextDueDate"));
        assertThat(soonDate).isAfter(today).isBeforeOrEqualTo(today.plusMonths(1));
    }

    @Test
    @DisplayName("예약 있음: 34나0002는 D+2일 10:00 유효 예약이 붙고, 예약이 없는 12가0001과 어제 예약뿐인 55차0010은 null (어제 예약은 예약 있음으로 세지 않음)")
    void activeReservation() throws Exception {
        String body = targets(ownerA);

        @SuppressWarnings("unchecked")
        Map<String, Object> reservation = (Map<String, Object>) row(body, "34나0002").get("activeReservation");
        assertThat(reservation).isNotNull();
        assertThat(((Number) reservation.get("reservationId")).longValue()).isEqualTo(reservationOf("34나0002"));
        assertThat(reservation.get("reservedAt")).isEqualTo(today.plusDays(2) + "T10:00:00");

        assertThat(row(body, "12가0001").get("activeReservation")).isNull();
        assertThat(row(body, "55차0010").get("activeReservation")).isNull();
    }

    @Test
    @DisplayName("08 17번 요구: 같은 차량의 14 targetItems와 17 itemStatuses는 상태·다음 정비일이 같다 (55차0010 배터리)")
    void targetItemsMatchPanelItemStatuses() throws Exception {
        String targets = targets(ownerA);
        String panel = mvc.perform(get("/api/reservations/" + reservationOf("55차0010") + "/panel")
                .header("Authorization", ownerA))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Map<String, Object>> inTargets = JsonPath.read(targets,
            "$.data.items[?(@.plateNumber=='55차0010')].targetItems[?(@.name=='배터리')]");
        List<Map<String, Object>> inPanel = JsonPath.read(panel, "$.data.itemStatuses[?(@.name=='배터리')]");

        assertThat(inTargets).hasSize(1);
        assertThat(inPanel).hasSize(1);
        assertThat(inTargets.get(0).get("status")).isEqualTo(inPanel.get(0).get("status")).isEqualTo("SOON");
        assertThat(inTargets.get(0).get("nextDueDate")).isEqualTo(inPanel.get(0).get("nextDueDate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> baseRecord = (Map<String, Object>) inPanel.get(0).get("baseRecord");
        assertThat(inTargets.get(0).get("baseDate")).isEqualTo(baseRecord.get("date"));
    }

    @Test
    @DisplayName("격리 X7: B 정비소 목록은 B의 강동원 12가0001(엔진오일 도래, 기준일 오늘 7개월 전) 한 줄뿐이고, 같은 차량번호의 A 줄(김민수)과 섞이지 않는다")
    void isolation() throws Exception {
        String bBody = targets(ownerB);
        String aBody = targets(ownerA);

        // B: 동의 고객 강동원의 12가0001 하나. 시드의 B 정비 기록은 엔진오일 교체 D-7M이라 6개월 주기가 한 달 전에 도래했다
        assertThat(plates(bBody)).containsExactly("12가0001");
        Map<String, Object> bRow = row(bBody, "12가0001");
        assertThat(bRow.get("customerName")).isEqualTo("강동원");
        assertThat(bRow.get("phone")).isEqualTo("010-****-0101");
        assertThat(bRow.get("activeReservation")).isNull();
        List<Map<String, Object>> bItems = JsonPath.read(bBody, "$.data.items[0].targetItems");
        assertThat(bItems).hasSize(1);
        assertThat(bItems.get(0).get("name")).isEqualTo("엔진오일");
        assertThat(bItems.get(0).get("status")).isEqualTo("DUE");
        LocalDate bBase = LocalDate.parse((String) bItems.get(0).get("baseDate"));
        assertThat(bBase).isEqualTo(today.minusMonths(7));
        assertThat(LocalDate.parse((String) bItems.get(0).get("nextDueDate"))).isEqualTo(bBase.plusMonths(6));

        // A: 같은 차량번호 12가0001이 정확히 한 줄이고 A 고객 김민수의 것이다(B 기록의 기준일과 다르다)
        assertThat(plates(aBody).stream().filter("12가0001"::equals)).hasSize(1);
        Map<String, Object> aRow = row(aBody, "12가0001");
        assertThat(aRow.get("customerName")).isEqualTo("김민수");
        List<Map<String, Object>> aItems = JsonPath.read(aBody, "$.data.items[?(@.plateNumber=='12가0001')].targetItems[0]");
        assertThat(LocalDate.parse((String) aItems.get(0).get("baseDate"))).isNotEqualTo(bBase);
        // B 고객(강동원)은 A 목록에 나오지 않는다 — 차량번호가 아니라 고객명 목록으로 확인한다(차량번호 목록으로는 항상 통과한다)
        List<String> aCustomerNames = JsonPath.read(aBody, "$.data.items[*].customerName");
        assertThat(aCustomerNames).doesNotContain("강동원");
    }

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"1234\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + JsonPath.<String>read(body, "$.data.accessToken");
    }
}
