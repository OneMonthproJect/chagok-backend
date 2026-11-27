package com.wanted.chagok.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * V1-11 시연 시드 적용 — chagok-docs 07 6.3 V1-11, 07 7장 시드 구성.
 *
 * <p>CI db-test의 빈 MySQL(V1·V2 적용)에 {@code db/seed/demo_seed.sql}을 <b>테스트 트랜잭션 안에서</b> 실행하고 끝나면 롤백한다.
 * 실제·개발·시연 DB에는 실행하지 않는다(시연 DB에는 {@code run-demo-seed.sh}로만 넣는다, README).
 *
 * <p>확인하는 것: 시드가 V1 제약(FK·CHECK·NOT NULL·UNIQUE)에 걸리지 않고 들어간다, 07 7.1~7.4의 건수·계정·담당·격리·상대 날짜,
 * 07 7.5 중 DB만으로 확인할 수 있는 값(오늘 예약 3건, A 고객 10명·차량 11대).
 *
 * <p>LIMIT: 07 7.5의 "오늘 연락할 고객 5명"·"재점검 대상 6대"와 차량별 기대 상태(도래·임박·여유·미확인)는 주기 계산(cycle)과
 * 대상 목록 API가 생긴 뒤 그 테스트에서 확인한다. 여기서는 그 상태를 만들 기록 날짜가 07대로 들어갔는지까지만 본다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class DemoSeedTest {

    private static final FileSystemResource SEED = new FileSystemResource("db/seed/demo_seed.sql");

    /** 시드와 같은 "지금"·"오늘": 서울 기준. MySQL 세션 시간대에 기대지 않는다 (07 7장, chagok-docs#110). */
    static final String NOW = "CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')";
    static final String TODAY = "DATE(" + NOW + ")";

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("V1-11 시연 시드를 V1·V2 위에 적용 → 성공, 07 7장 건수·계정·담당·격리·상대 날짜·V2 값, 7.5 오늘 예약 3건·A 고객 10·차량 11")
    void demoSeedAppliesOnV1AndMatchesSeedTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // run-demo-seed.sh와 같은 전제: shop이 비어 있는 DB
        assertThat(count(jdbc, "SELECT COUNT(*) FROM shop")).isZero();

        // 테스트 트랜잭션의 연결로 실행 → 테스트가 끝나면 롤백된다
        Connection connection = DataSourceUtils.getConnection(dataSource);
        ScriptUtils.executeSqlScript(connection, new EncodedResource(SEED, StandardCharsets.UTF_8));

        long shopA = id(jdbc, "SELECT id FROM shop WHERE name = '차곡카센터'");
        long shopB = id(jdbc, "SELECT id FROM shop WHERE name = '바른카센터'");

        // 건수 (07 7.1~7.4)
        Map<String, Integer> expectedCounts = Map.of(
            "platform_admin", 1, "shop", 2, "staff", 3, "service_item", 12, "customer", 11,
            "vehicle", 12, "maintenance_record", 17, "reservation", 5, "reservation_item", 4);
        expectedCounts.forEach((table, expected) ->
            assertThat(count(jdbc, "SELECT COUNT(*) FROM " + table)).as("rows of %s", table).isEqualTo(expected));

        // 7.1 (V2): 시연 정비소의 사업자등록번호·주소는 시연용 가짜 값 (숫자 10자리, 실제 번호·주소 아님)
        assertThat(jdbc.queryForList("SELECT CONCAT_WS('|', name, brn, shop_address) FROM shop ORDER BY id", String.class))
            .containsExactly("차곡카센터|000-00-00001|서울특별시 시연구 차곡로 1 (시연용)",
                "바른카센터|000-00-00002|서울특별시 시연구 바른로 2 (시연용)");
        assertThat(jdbc.queryForList("SELECT REPLACE(brn, '-', '') FROM shop", String.class)).allMatch(b -> b.matches("\\d{10}"));

        // 7.5: A 정비소 고객 10명·차량 11대, B는 1·1
        assertThat(count(jdbc, "SELECT COUNT(*) FROM customer WHERE shop_id = ?", shopA)).isEqualTo(10);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM vehicle WHERE shop_id = ?", shopA)).isEqualTo(11);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM customer WHERE shop_id = ?", shopB)).isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM vehicle WHERE shop_id = ?", shopB)).isEqualTo(1);

        // 7.1 계정: 비밀번호 1234의 BCrypt 해시, 첫 로그인 변경 강제 없음, 정비소·역할
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        List<Map<String, Object>> accounts = jdbc.queryForList(
            "SELECT s.login_id, s.name, s.role, s.password_hash, s.password_change_required, sh.name AS shop"
                + " FROM staff s JOIN shop sh ON sh.id = s.shop_id ORDER BY s.id");
        assertThat(accounts).extracting(a -> a.get("login_id") + "/" + a.get("name") + "/" + a.get("role") + "/" + a.get("shop"))
            .containsExactly("owner_a/박사장/OWNER/차곡카센터", "mech_a/김정비/MECHANIC/차곡카센터", "owner_b/최사장/OWNER/바른카센터");
        for (Map<String, Object> a : accounts) {
            assertThat(encoder.matches("1234", (String) a.get("password_hash"))).as("password of %s", a.get("login_id")).isTrue();
            assertThat(a.get("password_change_required")).as("change required of %s", a.get("login_id")).isIn(false, 0, (byte) 0);
        }
        Map<String, Object> admin = jdbc.queryForMap("SELECT login_id, name, password_hash FROM platform_admin");
        assertThat(admin.get("login_id")).isEqualTo("admin");
        assertThat(admin.get("name")).isEqualTo("운영관리자");
        assertThat(encoder.matches("1234", (String) admin.get("password_hash"))).isTrue();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM shop WHERE created_by_admin_id IS NOT NULL")).isZero();

        // 기본 기준표: 정비소마다 6행 (07 3.6)
        for (long shop : List.of(shopA, shopB)) {
            assertThat(jdbc.queryForList(
                "SELECT CONCAT(code, ':', cycle_months, ':', cycle_km) FROM service_item WHERE shop_id = ? AND active ORDER BY sort_order",
                String.class, shop))
                .containsExactly("ENGINE_OIL:6:10000", "AIR_CLEANER:12:15000", "AC_FILTER:6:10000",
                    "BRAKE_PAD:12:20000", "TIRE:6:10000", "BATTERY:36:60000");
        }

        // 7.2 동의: 장미래만 N
        assertThat(jdbc.queryForList("SELECT name FROM customer WHERE NOT consent", String.class)).containsExactly("장미래");

        // 7.2 차량: 차종은 모두 값 있음(D-58), 연식·주행거리 비움 확인용 차량
        assertThat(count(jdbc, "SELECT COUNT(*) FROM vehicle WHERE model IS NULL")).isZero();
        // 칼럼 타입별 JDBC 반환형 차이를 피하려고 문자열로 비교한다 ('-' = NULL)
        String yearAndMileage = "SELECT CONCAT_WS('|', model, COALESCE(model_year, '-'), COALESCE(latest_mileage, '-'))"
            + " FROM vehicle WHERE shop_id = ? AND plate_number = ?";
        assertThat(jdbc.queryForObject(yearAndMileage, String.class, shopA, "90마0005")).isEqualTo("아반떼|2019|-");
        assertThat(jdbc.queryForObject(yearAndMileage, String.class, shopA, "66카0011")).isEqualTo("K3|-|-");
        assertThat(jdbc.queryForObject(yearAndMileage, String.class, shopA, "78라0004")).isEqualTo("K5|2020|45000");
        assertThat(jdbc.queryForObject(yearAndMileage, String.class, shopA, "55차0010")).isEqualTo("쏘나타|2018|97000");
        // 차종 표기는 "쏘나타"로 통일 (07 7.2의 34나0002 "소나타" 표기는 docs에서 정리)
        assertThat(jdbc.queryForObject(yearAndMileage, String.class, shopA, "34나0002")).isEqualTo("쏘나타|2018|60000");
        // 한 고객 여러 차량: 오세훈 2대
        assertThat(count(jdbc, "SELECT COUNT(*) FROM vehicle v JOIN customer c ON c.id = v.customer_id WHERE c.name = '오세훈'"))
            .isEqualTo(2);

        // 7.4 격리: 같은 차량번호 12가0001이 두 정비소에 하나씩, 각자 자기 정비소 고객의 차량
        assertThat(jdbc.queryForList(
            "SELECT CONCAT(sh.name, ':', c.name) FROM vehicle v JOIN shop sh ON sh.id = v.shop_id"
                + " JOIN customer c ON c.id = v.customer_id AND c.shop_id = v.shop_id"
                + " WHERE v.plate_number = '12가0001' ORDER BY v.shop_id", String.class))
            .containsExactly("차곡카센터:김민수", "바른카센터:강동원");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM maintenance_record WHERE shop_id = ?", shopB)).isEqualTo(1);

        // 7.2·7.4 정비 기록: 차량·항목·구분·출처·날짜(오늘 기준 상대값)·주행거리가 07 표와 같다
        List<String> expectedRecords = List.of(
            "차곡카센터|12가0001|ENGINE_OIL|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 6 MONTH - INTERVAL 7 DAY") + "|40000",
            "차곡카센터|12가0001|ENGINE_OIL|INSPECT|SHOP|" + date(jdbc, TODAY + " - INTERVAL 2 MONTH") + "|-",
            "차곡카센터|34나0002|ENGINE_OIL|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 6 MONTH - INTERVAL 10 DAY") + "|-",
            "차곡카센터|56다0003|BRAKE_PAD|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 12 MONTH + INTERVAL 20 DAY") + "|-",
            "차곡카센터|78라0004|ENGINE_OIL|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 2 MONTH") + "|45000",
            "차곡카센터|11바0006|ENGINE_OIL|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 8 MONTH") + "|-",
            "차곡카센터|11바0006|ENGINE_OIL|DECLINE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 1 MONTH") + "|-",
            "차곡카센터|22사0007|AC_FILTER|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 13 MONTH") + "|-",
            "차곡카센터|22사0007|AC_FILTER|EXTERNAL|CUSTOMER_STATEMENT|" + date(jdbc, TODAY + " - INTERVAL 1 MONTH") + "|-",
            "차곡카센터|33아0008|TIRE|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 6 MONTH + INTERVAL 20 DAY") + "|-",
            "차곡카센터|33아0008|TIRE|INSPECT|SHOP|" + date(jdbc, TODAY + " - INTERVAL 2 MONTH") + "|-",
            "차곡카센터|44자0009|ENGINE_OIL|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 7 MONTH") + "|-",
            "차곡카센터|55차0010|BATTERY|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 36 MONTH + INTERVAL 20 DAY") + "|-",
            "차곡카센터|55차0010|AIR_CLEANER|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 3 MONTH") + "|-",
            "차곡카센터|55차0010|AC_FILTER|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 3 MONTH") + "|-",
            "차곡카센터|55차0010|ENGINE_OIL|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 1 MONTH") + "|-",
            "바른카센터|12가0001|ENGINE_OIL|REPLACE|SHOP|" + date(jdbc, TODAY + " - INTERVAL 7 MONTH") + "|-");
        assertThat(jdbc.queryForList(
            "SELECT CONCAT_WS('|', sh.name, v.plate_number, si.code, r.record_type, r.source, r.record_date, COALESCE(r.mileage, '-'))"
                + " FROM maintenance_record r JOIN shop sh ON sh.id = r.shop_id JOIN vehicle v ON v.id = r.vehicle_id"
                + " JOIN service_item si ON si.id = r.service_item_id ORDER BY r.id", String.class))
            .containsExactlyElementsOf(expectedRecords);
        // 7.2 (V2): 점검 결과 "주의"는 윤하늘 33아0008의 점검 기록 1건뿐이고 메모가 붙는다. 나머지 기록은 점검 결과·메모가 없다
        assertThat(jdbc.queryForList(
            "SELECT CONCAT_WS('|', v.plate_number, r.record_type, r.inspect_result, r.memo) FROM maintenance_record r"
                + " JOIN vehicle v ON v.id = r.vehicle_id WHERE r.inspect_result IS NOT NULL OR r.memo IS NOT NULL", String.class))
            .containsExactly("33아0008|INSPECT|CAUTION|타이어 트레드 마모 진행, 다음 방문 때 교체 권장");
        // 기록의 담당: A는 mech_a, B는 owner_b (시드 선택값). 이력 많은 차량 55차0010은 방문 3회
        assertThat(jdbc.queryForList(
            "SELECT DISTINCT CONCAT(sh.name, ':', s.login_id) FROM maintenance_record r JOIN shop sh ON sh.id = r.shop_id"
                + " JOIN staff s ON s.id = r.staff_id ORDER BY 1", String.class))
            .containsExactly("바른카센터:owner_b", "차곡카센터:mech_a");
        assertThat(count(jdbc, "SELECT COUNT(DISTINCT r.visit_key) FROM maintenance_record r JOIN vehicle v ON v.id = r.vehicle_id"
            + " WHERE v.shop_id = ? AND v.plate_number = '55차0010'", shopA)).isEqualTo(3);

        // 7.3 예약: 일시·상태·목적·담당 (담당 = 등록자)
        assertThat(jdbc.queryForList(
            "SELECT CONCAT_WS('|', v.plate_number, r.reserved_at, r.status, r.purpose_other, COALESCE(r.purpose_memo, '-'),"
                + " COALESCE((SELECT GROUP_CONCAT(si.code) FROM reservation_item ri JOIN service_item si ON si.id = ri.service_item_id"
                + " WHERE ri.reservation_id = r.id), '-'), a.login_id, c.login_id)"
                + " FROM reservation r JOIN vehicle v ON v.id = r.vehicle_id"
                + " JOIN staff a ON a.id = r.assignee_staff_id JOIN staff c ON c.id = r.created_by_staff_id"
                + " WHERE r.shop_id = ? ORDER BY r.id", String.class, shopA))
            .containsExactly(
                "78라0004|" + datetime(jdbc, "TIMESTAMP(" + TODAY + ", '10:00:00')") + "|RESERVED|0|-|ENGINE_OIL|mech_a|mech_a",
                "22사0007|" + datetime(jdbc, "TIMESTAMP(" + TODAY + ", '14:30:00')") + "|RESERVED|1|에어컨 냄새|-|owner_a|owner_a",
                "34나0002|" + datetime(jdbc, "TIMESTAMP(" + TODAY + " + INTERVAL 2 DAY, '10:00:00')") + "|RESERVED|0|-|ENGINE_OIL|mech_a|mech_a",
                "55차0010|" + datetime(jdbc, "TIMESTAMP(" + TODAY + " - INTERVAL 1 DAY, '15:00:00')") + "|RESERVED|0|-|BATTERY|owner_a|owner_a",
                "90마0005|" + datetime(jdbc, "TIMESTAMP(" + TODAY + ", '09:00:00')") + "|IN_PROGRESS|0|-|ENGINE_OIL|mech_a|mech_a");
        // 7.5: 홈 "오늘 예약" 3건 (진행중 1건 포함, 취소만 뺀다)
        assertThat(count(jdbc, "SELECT COUNT(*) FROM reservation WHERE shop_id = ? AND DATE(reserved_at) = " + TODAY, shopA))
            .isEqualTo(3);

        // 생성 시각: 미래 시각 없음, 정비 기록은 기록 날짜와 같은 날 (07 7장 "날짜 칼럼과 어긋나지 않게")
        for (String table : expectedCounts.keySet()) {
            assertThat(count(jdbc, "SELECT COUNT(*) FROM " + table + " WHERE created_at > " + NOW))
                .as("future created_at in %s", table).isZero();
        }
        assertThat(count(jdbc, "SELECT COUNT(*) FROM maintenance_record WHERE DATE(created_at) <> record_date")).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM reservation WHERE created_at > reserved_at")).isZero();
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static long id(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static String date(JdbcTemplate jdbc, String expression) {
        return jdbc.queryForObject("SELECT CAST(" + expression + " AS CHAR)", String.class);
    }

    private static String datetime(JdbcTemplate jdbc, String expression) {
        return jdbc.queryForObject("SELECT CAST(" + expression + " AS CHAR)", String.class);
    }
}
