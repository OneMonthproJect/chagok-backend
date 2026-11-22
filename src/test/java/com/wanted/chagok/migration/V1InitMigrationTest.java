package com.wanted.chagok.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * V1__init.sql 검증 — chagok-docs 07 6.3 체크리스트 V1-1 ~ V1-10, V1-12, V1-13 (TEST 명세서 6.1).
 *
 * <p>서버 검증을 거치지 않고 SQL을 직접 넣어 DB 제약이 막는지 본다. 거부는 MySQL 오류 번호까지 확인한다
 * (1452 FK, 3819 CHECK, 1062 UNIQUE, 1364 기본값 없음). 다른 이유로 실패해도 통과로 세지 않기 위해서다.
 * 같은 모양의 정상 INSERT가 성공하는 것도 함께 확인한다(양성 대조).
 *
 * <p>테스트마다 트랜잭션이 롤백되므로 DB에 행이 남지 않는다. MySQL이 필요하다: {@code ./gradlew dbTest}
 * (CI는 backend-ci의 db-test 작업, 빈 MySQL 8.0). V1-11(시연 시드 적용)은 시드 이슈에서 확인한다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class V1InitMigrationTest {

    private static final int FK_VIOLATION = 1452;
    private static final int CHECK_VIOLATION = 3819;
    private static final int DUPLICATE_KEY = 1062;
    private static final int NO_DEFAULT_VALUE = 1364;

    private static final String NOW = "2026-11-22 09:00:00";
    private static final String TODAY = "2026-11-22";
    private static final String VISIT_KEY = "00000000-0000-0000-0000-000000000001";

    private static final List<String> TABLES = List.of(
        "platform_admin", "shop", "staff", "customer", "service_item",
        "vehicle", "maintenance_record", "reservation", "reservation_item");

    private static final String VEHICLE_SQL =
        "INSERT INTO vehicle (shop_id, customer_id, plate_number, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
    private static final String RECORD_SQL =
        "INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date,"
            + " record_type, source, staff_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String RESERVATION_SQL =
        "INSERT INTO reservation (shop_id, vehicle_id, reserved_at, status, created_by_staff_id,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String RESERVATION_ITEM_SQL =
        "INSERT INTO reservation_item (shop_id, reservation_id, service_item_id, created_at) VALUES (?, ?, ?, ?)";
    private static final String SERVICE_ITEM_SQL =
        "INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, sort_order, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    // 정비소 A·B에 같은 모양의 데이터를 하나씩 만든다 (이 INSERT들이 같은 정비소 정상 저장의 양성 대조)
    private long shopA;
    private long shopB;
    private long staffA;
    private long staffB;
    private long customerA;
    private long customerB;
    private long itemA;
    private long itemB;
    private long vehicleA;
    private long vehicleB;
    private long reservationA;
    private long reservationB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);

        shopA = insert("INSERT INTO shop (name, phone, created_at) VALUES (?, ?, ?)", "V1 검증 A", "02-0000-0001", NOW);
        shopB = insert("INSERT INTO shop (name, phone, created_at) VALUES (?, ?, ?)", "V1 검증 B", "02-0000-0002", NOW);
        staffA = insertStaff(shopA, "v1test_owner_a");
        staffB = insertStaff(shopB, "v1test_owner_b");
        customerA = insertCustomer(shopA);
        customerB = insertCustomer(shopB);
        // 같은 code가 정비소만 다르면 허용된다 (UNIQUE(shop_id, code))
        itemA = insert(SERVICE_ITEM_SQL, shopA, "ENGINE_OIL", "엔진오일", 6, 10000, 1, NOW);
        itemB = insert(SERVICE_ITEM_SQL, shopB, "ENGINE_OIL", "엔진오일", 6, 10000, 1, NOW);
        vehicleA = insert(VEHICLE_SQL, shopA, customerA, "12가3456", NOW, NOW);
        vehicleB = insert(VEHICLE_SQL, shopB, customerB, "34나5678", NOW, NOW);
        reservationA = insert(RESERVATION_SQL, shopA, vehicleA, "2026-11-23 10:00:00", "RESERVED", staffA, NOW, NOW);
        reservationB = insert(RESERVATION_SQL, shopB, vehicleB, "2026-11-23 10:00:00", "RESERVED", staffB, NOW, NOW);
    }

    @Test
    @DisplayName("V1-1 빈 DB에 V1 적용·앱 기동(validate) 성공, 테이블 9개")
    void v1_1_appliedAndNineTables() {
        // 이 테스트가 실행된다는 것 자체가 Spring 컨텍스트(Flyway 적용 + ddl-auto: validate) 기동 성공이다
        Map<String, Object> history = jdbc.queryForMap(
            "SELECT version, success FROM flyway_schema_history WHERE version = '1'");
        assertThat(history.get("success")).isIn(true, 1, (byte) 1);

        Set<String> tables = new TreeSet<>(jdbc.queryForList(
            "SELECT table_name AS t FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
            String.class));
        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
    }

    @Test
    @DisplayName("V1-2 FK 목록: 복합 FK 6개 + 단일 FK 10개(shop_id 7, staff 2, admin 1), 모두 RESTRICT")
    void v1_2_foreignKeys() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT rc.constraint_name AS name, rc.table_name AS child, rc.referenced_table_name AS parent,"
                + " rc.delete_rule AS del, rc.update_rule AS upd,"
                + " GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position) AS cols,"
                + " GROUP_CONCAT(k.referenced_column_name ORDER BY k.ordinal_position) AS ref_cols"
                + " FROM information_schema.referential_constraints rc"
                + " JOIN information_schema.key_column_usage k"
                + "   ON k.constraint_schema = rc.constraint_schema"
                + "  AND k.constraint_name = rc.constraint_name"
                + "  AND k.table_name = rc.table_name"
                + " WHERE rc.constraint_schema = DATABASE()"
                + " GROUP BY rc.constraint_name, rc.table_name, rc.referenced_table_name, rc.delete_rule, rc.update_rule");

        Map<String, String> actual = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            actual.put((String) r.get("name"),
                r.get("child") + "(" + r.get("cols") + ")->" + r.get("parent") + "(" + r.get("ref_cols") + ")");
            // MySQL은 기본값(RESTRICT)을 NO ACTION으로 표시한다. InnoDB에서는 둘이 같다
            assertThat((String) r.get("del")).as("delete rule of %s", r.get("name")).isIn("NO ACTION", "RESTRICT");
            assertThat((String) r.get("upd")).as("update rule of %s", r.get("name")).isIn("NO ACTION", "RESTRICT");
        }

        Map<String, String> expected = new TreeMap<>(Map.ofEntries(
            // 복합 FK 6개 (07 5.1)
            Map.entry("fk_vehicle_customer", "vehicle(shop_id,customer_id)->customer(shop_id,id)"),
            Map.entry("fk_maintenance_record_vehicle", "maintenance_record(shop_id,vehicle_id)->vehicle(shop_id,id)"),
            Map.entry("fk_maintenance_record_service_item",
                "maintenance_record(shop_id,service_item_id)->service_item(shop_id,id)"),
            Map.entry("fk_reservation_vehicle", "reservation(shop_id,vehicle_id)->vehicle(shop_id,id)"),
            Map.entry("fk_reservation_item_reservation",
                "reservation_item(shop_id,reservation_id)->reservation(shop_id,id)"),
            Map.entry("fk_reservation_item_service_item",
                "reservation_item(shop_id,service_item_id)->service_item(shop_id,id)"),
            // 단일 FK: shop_id 7개
            Map.entry("fk_staff_shop", "staff(shop_id)->shop(id)"),
            Map.entry("fk_customer_shop", "customer(shop_id)->shop(id)"),
            Map.entry("fk_service_item_shop", "service_item(shop_id)->shop(id)"),
            Map.entry("fk_vehicle_shop", "vehicle(shop_id)->shop(id)"),
            Map.entry("fk_maintenance_record_shop", "maintenance_record(shop_id)->shop(id)"),
            Map.entry("fk_reservation_shop", "reservation(shop_id)->shop(id)"),
            Map.entry("fk_reservation_item_shop", "reservation_item(shop_id)->shop(id)"),
            // 단일 FK: staff 2개, platform_admin 1개
            Map.entry("fk_maintenance_record_staff", "maintenance_record(staff_id)->staff(id)"),
            Map.entry("fk_reservation_staff", "reservation(created_by_staff_id)->staff(id)"),
            Map.entry("fk_shop_platform_admin", "shop(created_by_admin_id)->platform_admin(id)")));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("V1-3 A 정비소 고객을 가리키는 B 정비소 vehicle INSERT → 1452")
    void v1_3_vehicleCannotPointToOtherShopsCustomer() {
        assertRejected(FK_VIOLATION, VEHICLE_SQL, shopB, customerA, "56다7890", NOW, NOW);
    }

    @Test
    @DisplayName("V1-4 B 정비소 차량·항목으로 A 정비소 maintenance_record·reservation INSERT → 1452")
    void v1_4_recordAndReservationCannotPointToOtherShopsParents() {
        // 양성 대조: 같은 정비소 부모로는 저장된다
        insert(RECORD_SQL, shopA, vehicleA, itemA, VISIT_KEY, TODAY, "REPLACE", "SHOP", staffA, NOW);

        assertRejected(FK_VIOLATION, RECORD_SQL, shopA, vehicleB, itemA, VISIT_KEY, TODAY, "REPLACE", "SHOP", staffA, NOW);
        assertRejected(FK_VIOLATION, RECORD_SQL, shopA, vehicleA, itemB, VISIT_KEY, TODAY, "REPLACE", "SHOP", staffA, NOW);
        assertRejected(FK_VIOLATION, RESERVATION_SQL,
            shopA, vehicleB, "2026-11-24 10:00:00", "RESERVED", staffA, NOW, NOW);
    }

    @Test
    @DisplayName("V1-5 B 정비소 예약·항목을 가리키는 A 정비소 reservation_item INSERT → 1452")
    void v1_5_reservationItemCannotPointToOtherShopsParents() {
        insert(RESERVATION_ITEM_SQL, shopA, reservationA, itemA, NOW); // 양성 대조

        assertRejected(FK_VIOLATION, RESERVATION_ITEM_SQL, shopA, reservationB, itemA, NOW);
        assertRejected(FK_VIOLATION, RESERVATION_ITEM_SQL, shopA, reservationA, itemB, NOW);
    }

    @Test
    @DisplayName("V1-6 reserved_at 10:15 / 10:30:15 INSERT → 3819 (CHECK)")
    void v1_6_reservedAtMustBeHalfHourWithZeroSeconds() {
        insert(RESERVATION_SQL, shopA, vehicleA, "2026-11-24 10:30:00", "RESERVED", staffA, NOW, NOW); // 양성 대조

        assertRejected(CHECK_VIOLATION, RESERVATION_SQL,
            shopA, vehicleA, "2026-11-24 10:15:00", "RESERVED", staffA, NOW, NOW);
        assertRejected(CHECK_VIOLATION, RESERVATION_SQL,
            shopA, vehicleA, "2026-11-24 10:30:15", "RESERVED", staffA, NOW, NOW);
    }

    @Test
    @DisplayName("V1-7 record_type = 'EXTERNAL' + source = 'SHOP' INSERT → 3819 (CHECK)")
    void v1_7_externalMustComeFromCustomerStatement() {
        // 양성 대조: 외부 ↔ 고객 진술, 나머지 ↔ SHOP
        insert(RECORD_SQL, shopA, vehicleA, itemA, VISIT_KEY, TODAY, "EXTERNAL", "CUSTOMER_STATEMENT", staffA, NOW);
        insert(RECORD_SQL, shopA, vehicleA, itemA, VISIT_KEY, TODAY, "INSPECT", "SHOP", staffA, NOW);

        assertRejected(CHECK_VIOLATION, RECORD_SQL,
            shopA, vehicleA, itemA, VISIT_KEY, TODAY, "EXTERNAL", "SHOP", staffA, NOW);
        // 반대 방향도 막힌다
        assertRejected(CHECK_VIOLATION, RECORD_SQL,
            shopA, vehicleA, itemA, VISIT_KEY, TODAY, "REPLACE", "CUSTOMER_STATEMENT", staffA, NOW);
    }

    @Test
    @DisplayName("V1-8 reservation.status = 'NO_SHOW' INSERT → 3819 (V1에는 없음, V2에서 허용)")
    void v1_8_noShowIsNotAllowedInV1() {
        assertRejected(CHECK_VIOLATION, RESERVATION_SQL,
            shopA, vehicleA, "2026-11-24 11:00:00", "NO_SHOW", staffA, NOW, NOW);
    }

    @Test
    @DisplayName("V1-9 같은 정비소 service_item.code 중복, 같은 예약에 같은 항목 두 번 → 1062 (UNIQUE)")
    void v1_9_uniqueConstraints() {
        // 양성 대조: 다른 정비소의 같은 code는 setUp에서 이미 저장됨 (itemA·itemB 모두 ENGINE_OIL)
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM service_item WHERE code = 'ENGINE_OIL' AND shop_id IN (?, ?)",
            Integer.class, shopA, shopB)).isEqualTo(2);

        assertRejected(DUPLICATE_KEY, SERVICE_ITEM_SQL, shopA, "ENGINE_OIL", "엔진오일2", 6, 10000, 7, NOW);

        insert(RESERVATION_ITEM_SQL, shopA, reservationA, itemA, NOW);
        assertRejected(DUPLICATE_KEY, RESERVATION_ITEM_SQL, shopA, reservationA, itemA, NOW);
    }

    @Test
    @DisplayName("V1-10 같은 정비소 같은 plate_number 차량 2대 → 성공 (중복 허용, POL-32)")
    void v1_10_duplicatePlateNumberIsAllowed() {
        insert(VEHICLE_SQL, shopA, customerA, "12가3456", NOW, NOW);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM vehicle WHERE shop_id = ? AND plate_number = '12가3456'",
            Integer.class, shopA)).isEqualTo(2);
    }

    @Test
    @DisplayName("V1-12 V1에 P0-나머지·P1 칼럼·값이 없음 (칼럼 목록이 07 3장과 같음)")
    void v1_12_noLaterStageColumnsOrValues() {
        Map<String, List<String>> expectedColumns = Map.of(
            "platform_admin", List.of("id", "login_id", "password_hash", "name", "created_at"),
            "shop", List.of("id", "name", "phone", "status", "created_by_admin_id", "created_at"),
            "staff", List.of("id", "shop_id", "login_id", "password_hash", "name", "role",
                "password_change_required", "created_at"),
            "customer", List.of("id", "shop_id", "name", "phone", "consent", "created_at", "updated_at"),
            "service_item", List.of("id", "shop_id", "code", "name", "cycle_months", "cycle_km", "active",
                "sort_order", "created_at"),
            "vehicle", List.of("id", "shop_id", "customer_id", "plate_number", "model", "model_year",
                "latest_mileage", "created_at", "updated_at"),
            "maintenance_record", List.of("id", "shop_id", "vehicle_id", "service_item_id", "visit_key",
                "record_date", "mileage", "record_type", "source", "staff_id", "memo", "created_at"),
            "reservation", List.of("id", "shop_id", "vehicle_id", "reserved_at", "status", "purpose_other",
                "purpose_memo", "created_by_staff_id", "version", "created_at", "updated_at"),
            "reservation_item", List.of("id", "shop_id", "reservation_id", "service_item_id", "created_at"));

        for (String table : TABLES) {
            List<String> columns = jdbc.queryForList(
                "SELECT column_name AS c FROM information_schema.columns"
                    + " WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
                String.class, table);
            assertThat(columns).as("columns of %s", table).isEqualTo(expectedColumns.get(table));
        }
        // 이 단언들은 위 목록에 이미 포함되지만, 07 4장의 대상을 이름으로 남긴다
        assertThat(expectedColumns.get("maintenance_record")).doesNotContain("inspect_result");
        assertThat(expectedColumns.get("customer")).doesNotContain("version");
        assertThat(expectedColumns.get("vehicle")).doesNotContain("version");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'contact_log'",
            Integer.class)).isZero();

        String statusCheck = jdbc.queryForObject(
            "SELECT check_clause FROM information_schema.check_constraints"
                + " WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_reservation_status'",
            String.class);
        assertThat(statusCheck).contains("RESERVED", "COMPLETED", "CANCELED").doesNotContain("NO_SHOW");
    }

    @Test
    @DisplayName("V1-13 created_at·updated_at을 빼고 INSERT → 1364, SHOW CREATE TABLE에 DEFAULT·ON UPDATE 없음")
    void v1_13_timestampsHaveNoDatabaseDefault() {
        // 1364는 strict 모드에서만 오류다. 모드가 꺼져 있으면 이 검증이 의미 없으므로 먼저 확인한다
        assertThat(jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class)).contains("STRICT_TRANS_TABLES");

        insert("INSERT INTO customer (shop_id, name, phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            shopA, "양성 대조", "010-0000-0003", NOW, NOW);
        assertRejected(NO_DEFAULT_VALUE,
            "INSERT INTO customer (shop_id, name, phone, updated_at) VALUES (?, ?, ?, ?)",
            shopA, "생성 시각 없음", "010-0000-0004", NOW);
        assertRejected(NO_DEFAULT_VALUE,
            "INSERT INTO customer (shop_id, name, phone, created_at) VALUES (?, ?, ?, ?)",
            shopA, "수정 시각 없음", "010-0000-0005", NOW);
        assertRejected(NO_DEFAULT_VALUE,
            "INSERT INTO shop (name, phone) VALUES (?, ?)", "생성 시각 없음", "02-0000-0009");

        int timestampColumns = 0;
        for (String table : TABLES) {
            String ddl = jdbc.queryForObject("SHOW CREATE TABLE " + table, (rs, i) -> rs.getString(2));
            for (String line : ddl.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("`created_at`") || trimmed.startsWith("`updated_at`")) {
                    timestampColumns++;
                    // 기대 모양: `created_at` datetime NOT NULL,  (DEFAULT · ON UPDATE 없음)
                    assertThat(trimmed).as("%s: %s", table, trimmed)
                        .matches("`(created|updated)_at` datetime NOT NULL,?")
                        .doesNotContainIgnoringCase("DEFAULT")
                        .doesNotContainIgnoringCase("ON UPDATE");
                }
            }
        }
        // created_at 9개 + updated_at 3개 (customer · vehicle · reservation)
        assertThat(timestampColumns).isEqualTo(12);
    }

    // ---------- helpers ----------

    private long insertStaff(long shopId, String loginId) {
        return insert("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)", shopId, loginId, "{noop}x", "검증 사장", "OWNER", NOW);
    }

    private long insertCustomer(long shopId) {
        return insert("INSERT INTO customer (shop_id, name, phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            shopId, "검증 고객", "010-0000-0001", NOW, NOW);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void assertRejected(int expectedErrorCode, String sql, Object... args) {
        Throwable thrown = catchThrowable(() -> jdbc.update(sql, args));
        assertThat(thrown).as("should be rejected: %s", sql).isNotNull();
        assertThat(mysqlErrorCode(thrown)).as("MySQL error code for: %s (%s)", sql, thrown.getMessage())
            .isEqualTo(expectedErrorCode);
    }

    private static int mysqlErrorCode(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException e && e.getErrorCode() != 0) {
                return e.getErrorCode();
            }
        }
        return -1;
    }
}
