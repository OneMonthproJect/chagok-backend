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
 * V2(P0-나머지, chagok-backend#31)가 적용된 DB 기준이다. V1-1·V1-2·V1-8·V1-12·V1-13은 V2 기대값으로 옮겼다(아래 LIMIT).
 *
 * <p>서버 검증을 거치지 않고 SQL을 직접 넣어 DB 제약이 막는지 본다. 거부는 MySQL 오류 번호까지 확인한다
 * (1452 FK, 3819 CHECK, 1062 UNIQUE, 1364 기본값 없음). 다른 이유로 실패해도 통과로 세지 않기 위해서다.
 * 같은 모양의 정상 INSERT가 성공하는 것도 함께 확인한다(양성 대조).
 *
 * <p>테스트마다 트랜잭션이 롤백되므로 DB에 행이 남지 않는다. MySQL이 필요하다: {@code ./gradlew dbTest}
 * (CI는 backend-ci의 db-test 작업, 빈 MySQL 8.0). V1-11(시연 시드 적용)은 시드 이슈에서 확인한다.
 *
 * <p>담당 정비사(D-51·D-57): {@code reservation.assignee_staff_id}와 {@code maintenance_record.staff_id}는
 * {@code staff(shop_id, id)}를 가리키는 복합 FK다. V1-2(이름·칼럼 조합)와 V1-4(다른 정비소 거부 + 같은 정비소 허용),
 * 담당 필수 확인(V1-4 보강)에서 본다.
 *
 * <p>LIMIT: 이 테스트는 최신 마이그레이션까지 적용된 DB에서 돌므로, 버전이 들어올 때마다 기대값을 그 버전 기준으로 옮긴다(07 6장).
 * V2(P0-나머지)에서 옮긴 것: V1-1(테이블 11개: 이력 표 2개)·V1-2(이력 표 FK·UNIQUE, {@code maintenance_record}
 * UNIQUE(shop_id, id))·V1-8({@code IN_PROGRESS}·{@code NO_SHOW} 허용)·V1-12({@code inspect_result},
 * {@code maintenance_record.updated_at}·{@code version}, {@code customer.version}·{@code vehicle.version}, 이력 표)·
 * V1-13(타임스탬프 칼럼 수: {@code maintenance_record.updated_at}, 이력 표 {@code created_at}).
 * 남은 것은 V3(P1) PR: V1-1·V1-2({@code contact_log})·V1-12({@code customer.status}·{@code anonymized_at},
 * {@code vehicle.active}, {@code staff.active}, {@code contact_log}).
 * 명세가 바뀐 만큼만 옮기는 것이고 검증을 약하게 하는 것이 아니다(CONVENTIONS 12장).
 */
@Tag("db")
@SpringBootTest
@Transactional
class V1InitMigrationTest {

    private static final int FK_VIOLATION = 1452;
    private static final int CHECK_VIOLATION = 3819;
    private static final int DUPLICATE_KEY = 1062;
    private static final int NO_DEFAULT_VALUE = 1364;
    private static final int COLUMN_CANNOT_BE_NULL = 1048;

    private static final String NOW = "2026-11-22 09:00:00";
    private static final String TODAY = "2026-11-22";
    private static final String VISIT_KEY = "00000000-0000-0000-0000-000000000001";

    private static final List<String> TABLES = List.of(
        "platform_admin", "shop", "staff", "customer", "service_item",
        "vehicle", "maintenance_record", "reservation", "reservation_item",
        "maintenance_record_revision", "reservation_revision");

    private static final String VEHICLE_SQL =
        "INSERT INTO vehicle (shop_id, customer_id, plate_number, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
    private static final String RECORD_SQL =
        // V2부터 updated_at NOT NULL (DEFAULT 없음). 호출부 인자 수를 그대로 두려고 updated_at은 NOW 상수로 넣는다
        "INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date,"
            + " record_type, source, staff_id, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '" + NOW + "')";
    // 인자 순서: shop_id, vehicle_id, reserved_at, status, assignee_staff_id(담당), created_by_staff_id(등록자), 시각 2개
    private static final String RESERVATION_SQL =
        "INSERT INTO reservation (shop_id, vehicle_id, reserved_at, status, assignee_staff_id, created_by_staff_id,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
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
    private long staffA;     // A 정비소 사장
    private long mechanicA;  // A 정비소 정비사 (등록자와 다른 담당 정비사의 양성 대조)
    private long staffB;     // B 정비소 사장
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
        staffA = insertStaff(shopA, "v1test_owner_a", "OWNER");
        mechanicA = insertStaff(shopA, "v1test_mech_a", "MECHANIC");
        staffB = insertStaff(shopB, "v1test_owner_b", "OWNER");
        customerA = insertCustomer(shopA);
        customerB = insertCustomer(shopB);
        // 같은 code가 정비소만 다르면 허용된다 (UNIQUE(shop_id, code))
        itemA = insert(SERVICE_ITEM_SQL, shopA, "ENGINE_OIL", "엔진오일", 6, 10000, 1, NOW);
        itemB = insert(SERVICE_ITEM_SQL, shopB, "ENGINE_OIL", "엔진오일", 6, 10000, 1, NOW);
        vehicleA = insert(VEHICLE_SQL, shopA, customerA, "12가3456", NOW, NOW);
        vehicleB = insert(VEHICLE_SQL, shopB, customerB, "34나5678", NOW, NOW);
        // P0-핵심 모양: 담당 = 등록자 (서버가 등록자로 채움, 07 3.8)
        reservationA = insert(RESERVATION_SQL,
            shopA, vehicleA, "2026-11-23 10:00:00", "RESERVED", staffA, staffA, NOW, NOW);
        reservationB = insert(RESERVATION_SQL,
            shopB, vehicleB, "2026-11-23 10:00:00", "RESERVED", staffB, staffB, NOW, NOW);
    }

    @Test
    @DisplayName("V1-1 빈 DB에 V1→V2 적용·앱 기동(validate) 성공, 테이블 11개")
    void v1_1_appliedAndElevenTables() {
        // 이 테스트가 실행된다는 것 자체가 Spring 컨텍스트(Flyway 적용 + ddl-auto: validate) 기동 성공이다
        for (String version : List.of("1", "2")) {
            Map<String, Object> history = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = ?", version);
            assertThat(history.get("success")).as("flyway V%s success", version).isIn(true, 1, (byte) 1);
        }

        Set<String> tables = new TreeSet<>(jdbc.queryForList(
            "SELECT table_name AS t FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
            String.class));
        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
    }

    @Test
    @DisplayName("V1-2 FK: 복합 10개 + 단일 13개(shop_id 9, 계정 3, admin 1)의 이름·칼럼 조합, 모두 RESTRICT")
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

        Map<String, String> expectedComposite = new TreeMap<>(Map.ofEntries(
            // 복합 FK 10개 (07 5.1): 기존 6개 + 담당 정비사 2개 (D-51·D-57) + 이력 표 2개 (V2)
            Map.entry("fk_vehicle_customer", "vehicle(shop_id,customer_id)->customer(shop_id,id)"),
            Map.entry("fk_maintenance_record_vehicle", "maintenance_record(shop_id,vehicle_id)->vehicle(shop_id,id)"),
            Map.entry("fk_maintenance_record_service_item",
                "maintenance_record(shop_id,service_item_id)->service_item(shop_id,id)"),
            Map.entry("fk_reservation_vehicle", "reservation(shop_id,vehicle_id)->vehicle(shop_id,id)"),
            Map.entry("fk_reservation_item_reservation",
                "reservation_item(shop_id,reservation_id)->reservation(shop_id,id)"),
            Map.entry("fk_reservation_item_service_item",
                "reservation_item(shop_id,service_item_id)->service_item(shop_id,id)"),
            Map.entry("fk_reservation_assignee_staff", "reservation(shop_id,assignee_staff_id)->staff(shop_id,id)"),
            Map.entry("fk_maintenance_record_staff", "maintenance_record(shop_id,staff_id)->staff(shop_id,id)"),
            Map.entry("fk_maintenance_record_revision_maintenance_record",
                "maintenance_record_revision(shop_id,record_id)->maintenance_record(shop_id,id)"),
            Map.entry("fk_reservation_revision_reservation",
                "reservation_revision(shop_id,reservation_id)->reservation(shop_id,id)")));

        Map<String, String> expectedSingle = new TreeMap<>(Map.ofEntries(
            // 단일 FK: shop_id 9개 (이력 표 2개 포함)
            Map.entry("fk_staff_shop", "staff(shop_id)->shop(id)"),
            Map.entry("fk_customer_shop", "customer(shop_id)->shop(id)"),
            Map.entry("fk_service_item_shop", "service_item(shop_id)->shop(id)"),
            Map.entry("fk_vehicle_shop", "vehicle(shop_id)->shop(id)"),
            Map.entry("fk_maintenance_record_shop", "maintenance_record(shop_id)->shop(id)"),
            Map.entry("fk_reservation_shop", "reservation(shop_id)->shop(id)"),
            Map.entry("fk_reservation_item_shop", "reservation_item(shop_id)->shop(id)"),
            Map.entry("fk_maintenance_record_revision_shop", "maintenance_record_revision(shop_id)->shop(id)"),
            Map.entry("fk_reservation_revision_shop", "reservation_revision(shop_id)->shop(id)"),
            // 단일 FK: 계정(토큰 값) 3개 — 예약 등록자, 이력 수정자 2개. platform_admin 1개
            Map.entry("fk_reservation_staff", "reservation(created_by_staff_id)->staff(id)"),
            Map.entry("fk_maintenance_record_revision_staff",
                "maintenance_record_revision(changed_by_staff_id)->staff(id)"),
            Map.entry("fk_reservation_revision_staff", "reservation_revision(changed_by_staff_id)->staff(id)"),
            Map.entry("fk_shop_platform_admin", "shop(created_by_admin_id)->platform_admin(id)")));

        assertThat(expectedComposite).hasSize(10);
        assertThat(expectedSingle).hasSize(13);
        Map<String, String> expected = new TreeMap<>(expectedComposite);
        expected.putAll(expectedSingle);
        assertThat(actual).isEqualTo(expected);

        // UNIQUE 이름·칼럼 조합. (shop_id, id)는 복합 FK의 부모 키로 정확히 6개 테이블에 있다 (07 5.1, V2에서 maintenance_record 추가)
        List<Map<String, Object>> uniques = jdbc.queryForList(
            "SELECT tc.constraint_name AS name, tc.table_name AS tbl,"
                + " GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position) AS cols"
                + " FROM information_schema.table_constraints tc"
                + " JOIN information_schema.key_column_usage k"
                + "   ON k.constraint_schema = tc.constraint_schema"
                + "  AND k.constraint_name = tc.constraint_name"
                + "  AND k.table_name = tc.table_name"
                + " WHERE tc.constraint_schema = DATABASE() AND tc.constraint_type = 'UNIQUE'"
                + " GROUP BY tc.constraint_name, tc.table_name");
        Map<String, String> actualUniques = new TreeMap<>();
        for (Map<String, Object> r : uniques) {
            actualUniques.put((String) r.get("name"), r.get("tbl") + "(" + r.get("cols") + ")");
        }
        assertThat(actualUniques).isEqualTo(new TreeMap<>(Map.ofEntries(
            Map.entry("uk_platform_admin_login_id", "platform_admin(login_id)"),
            Map.entry("uk_staff_login_id", "staff(login_id)"),
            Map.entry("uk_staff_shop_id_id", "staff(shop_id,id)"),
            Map.entry("uk_customer_shop_id_id", "customer(shop_id,id)"),
            Map.entry("uk_service_item_shop_id_code", "service_item(shop_id,code)"),
            Map.entry("uk_service_item_shop_id_id", "service_item(shop_id,id)"),
            Map.entry("uk_vehicle_shop_id_id", "vehicle(shop_id,id)"),
            Map.entry("uk_maintenance_record_shop_id_id", "maintenance_record(shop_id,id)"),
            Map.entry("uk_maintenance_record_revision_record_id_revision_no",
                "maintenance_record_revision(record_id,revision_no)"),
            Map.entry("uk_reservation_shop_id_id", "reservation(shop_id,id)"),
            Map.entry("uk_reservation_item_reservation_id_service_item_id",
                "reservation_item(reservation_id,service_item_id)"))));
        assertThat(actualUniques.values().stream().filter(v -> v.endsWith("(shop_id,id)")))
            .containsExactlyInAnyOrder("staff(shop_id,id)", "customer(shop_id,id)", "service_item(shop_id,id)",
                "vehicle(shop_id,id)", "maintenance_record(shop_id,id)", "reservation(shop_id,id)");

        // 담당 정비사 복합 FK를 받치는 인덱스 (07 3.7·3.8, 6.1)
        assertThat(indexColumns("maintenance_record", "idx_maintenance_record_shop_id_staff_id"))
            .isEqualTo("shop_id,staff_id");
        assertThat(indexColumns("reservation", "idx_reservation_shop_id_assignee_staff_id_reserved_at"))
            .isEqualTo("shop_id,assignee_staff_id,reserved_at");
        assertThat(indexColumns("maintenance_record", "idx_maintenance_record_staff_id"))
            .as("단일 FK 시절 인덱스는 없어야 한다").isNull();

        // V2 이력 표 인덱스 (07 4.1·4.2, 회의 결정 1·3)
        assertThat(indexColumns("maintenance_record_revision", "idx_maintenance_record_revision_shop_id_record_id"))
            .isEqualTo("shop_id,record_id");
        assertThat(indexColumns("maintenance_record_revision", "idx_maintenance_record_revision_changed_by_staff_id"))
            .isEqualTo("changed_by_staff_id");
        assertThat(indexColumns("reservation_revision", "idx_reservation_revision_shop_id_reservation_id_created_at"))
            .isEqualTo("shop_id,reservation_id,created_at");
        assertThat(indexColumns("reservation_revision", "idx_reservation_revision_changed_by_staff_id"))
            .isEqualTo("changed_by_staff_id");
    }

    @Test
    @DisplayName("V1-3 A 정비소 고객을 가리키는 B 정비소 vehicle INSERT → 1452")
    void v1_3_vehicleCannotPointToOtherShopsCustomer() {
        assertRejected(FK_VIOLATION, VEHICLE_SQL, shopB, customerA, "56다7890", NOW, NOW);
    }

    @Test
    @DisplayName("V1-4 B 정비소 차량·항목·정비사로 A 정비소 maintenance_record·reservation INSERT → 1452, 같은 정비소는 성공")
    void v1_4_recordAndReservationCannotPointToOtherShopsParents() {
        // 양성 대조: 같은 정비소 부모로는 저장된다
        insert(RECORD_SQL, shopA, vehicleA, itemA, VISIT_KEY, TODAY, "REPLACE", "SHOP", staffA, NOW);

        assertRejected(FK_VIOLATION, RECORD_SQL, shopA, vehicleB, itemA, VISIT_KEY, TODAY, "REPLACE", "SHOP", staffA, NOW);
        assertRejected(FK_VIOLATION, RECORD_SQL, shopA, vehicleA, itemB, VISIT_KEY, TODAY, "REPLACE", "SHOP", staffA, NOW);
        assertRejected(FK_VIOLATION, RESERVATION_SQL,
            shopA, vehicleB, "2026-11-24 10:00:00", "RESERVED", staffA, staffA, NOW, NOW);
    }

    @Test
    @DisplayName("V1-4 담당 정비사: 같은 정비소 정비사는 저장, B 정비소 정비사 → 1452 (reservation·maintenance_record)")
    void v1_4_assigneeMustBelongToSameShop() {
        // 양성 대조: 같은 정비소의 다른 계정(정비사)을 담당으로 저장할 수 있다 (등록자 ≠ 담당, P0-나머지 모양)
        long reservation = insert(RESERVATION_SQL,
            shopA, vehicleA, "2026-11-24 10:00:00", "RESERVED", mechanicA, staffA, NOW, NOW);
        assertThat(jdbc.queryForMap(
            "SELECT shop_id, assignee_staff_id, created_by_staff_id FROM reservation WHERE id = ?", reservation))
            .containsEntry("shop_id", shopA)
            .containsEntry("assignee_staff_id", mechanicA)
            .containsEntry("created_by_staff_id", staffA);
        long record = insert(RECORD_SQL, shopA, vehicleA, itemA, VISIT_KEY, TODAY, "REPLACE", "SHOP", mechanicA, NOW);
        assertThat(jdbc.queryForMap("SELECT shop_id, staff_id FROM maintenance_record WHERE id = ?", record))
            .containsEntry("shop_id", shopA)
            .containsEntry("staff_id", mechanicA);

        // 다른 정비소 정비사를 담당으로 → 복합 FK가 막는다 (07 5.1, D-51·D-57)
        assertRejectedBy(FK_VIOLATION, "fk_reservation_assignee_staff", RESERVATION_SQL,
            shopA, vehicleA, "2026-11-24 11:00:00", "RESERVED", staffB, staffA, NOW, NOW);
        assertRejectedBy(FK_VIOLATION, "fk_maintenance_record_staff", RECORD_SQL,
            shopA, vehicleA, itemA, VISIT_KEY, TODAY, "REPLACE", "SHOP", staffB, NOW);
    }

    @Test
    @DisplayName("V1-4 보강: reservation.assignee_staff_id 필수 — 빼면 1364, NULL이면 1048")
    void v1_4_assigneeStaffIsRequired() {
        // 1364는 strict 모드에서만 오류다. 모드가 꺼져 있으면 이 검증이 의미 없으므로 먼저 확인한다
        assertThat(jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class)).contains("STRICT_TRANS_TABLES");

        assertThat(jdbc.queryForMap(
            "SELECT is_nullable, column_default, data_type FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = 'reservation' AND column_name = 'assignee_staff_id'"))
            .containsEntry("is_nullable", "NO")
            .containsEntry("column_default", null)
            .containsEntry("data_type", "bigint");

        assertRejected(NO_DEFAULT_VALUE,
            "INSERT INTO reservation (shop_id, vehicle_id, reserved_at, status, created_by_staff_id, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
            shopA, vehicleA, "2026-11-24 12:00:00", "RESERVED", staffA, NOW, NOW);
        assertRejected(COLUMN_CANNOT_BE_NULL, RESERVATION_SQL,
            shopA, vehicleA, "2026-11-24 12:00:00", "RESERVED", null, staffA, NOW, NOW);
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
        insert(RESERVATION_SQL, shopA, vehicleA, "2026-11-24 10:30:00", "RESERVED", staffA, staffA, NOW, NOW); // 양성 대조

        assertRejectedBy(CHECK_VIOLATION, "ck_reservation_reserved_at", RESERVATION_SQL,
            shopA, vehicleA, "2026-11-24 10:15:00", "RESERVED", staffA, staffA, NOW, NOW);
        assertRejectedBy(CHECK_VIOLATION, "ck_reservation_reserved_at", RESERVATION_SQL,
            shopA, vehicleA, "2026-11-24 10:30:15", "RESERVED", staffA, staffA, NOW, NOW);
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
    @DisplayName("V1-8 reservation.status = 'IN_PROGRESS'·'NO_SHOW' INSERT 성공 (V2), 허용 밖의 값은 3819 ck_reservation_status")
    void v1_8_inProgressAndNoShowAreAllowedInV2() {
        // 기존 3개(V1)와 V2가 더한 2개가 모두 저장된다. 30분 단위 시각(ck_reservation_reserved_at)을 상태마다 달리 쓴다
        List<String> statuses = List.of("RESERVED", "COMPLETED", "CANCELED", "IN_PROGRESS", "NO_SHOW");
        for (int i = 0; i < statuses.size(); i++) {
            String reservedAt = "2026-11-24 %02d:%02d:00".formatted(11 + i / 2, i % 2 * 30);
            insert(RESERVATION_SQL, shopA, vehicleA, reservedAt, statuses.get(i), staffA, staffA, NOW, NOW);
        }

        // 같은 행 모양에 허용 밖의 값이면 상태 CHECK가 막는다 (거부 이유를 ck_reservation_status로 좁힘)
        assertRejectedBy(CHECK_VIOLATION, "ck_reservation_status", RESERVATION_SQL,
            shopA, vehicleA, "2026-11-24 14:00:00", "DONE", staffA, staffA, NOW, NOW);
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
    @DisplayName("V1-12 V2에 P0-나머지 표·칼럼·값이 있고 P1 표·칼럼은 아직 없음, 있어야 할 칼럼(assignee_staff_id 등)은 있음")
    void v1_12_noLaterStageColumnsOrValues() {
        Map<String, List<String>> expectedColumns = Map.ofEntries(
            Map.entry("platform_admin", List.of("id", "login_id", "password_hash", "name", "created_at")),
            // V2: shop에 사업자등록번호(brn)·정비소 주소
            Map.entry("shop", List.of("id", "name", "phone", "status", "created_by_admin_id", "created_at",
                "brn", "shop_address")),
            Map.entry("staff", List.of("id", "shop_id", "login_id", "password_hash", "name", "role",
                "password_change_required", "created_at")),
            // V2: customer·vehicle에 version
            Map.entry("customer", List.of("id", "shop_id", "name", "phone", "consent", "created_at", "updated_at",
                "version")),
            Map.entry("service_item", List.of("id", "shop_id", "code", "name", "cycle_months", "cycle_km", "active",
                "sort_order", "created_at")),
            Map.entry("vehicle", List.of("id", "shop_id", "customer_id", "plate_number", "model", "model_year",
                "latest_mileage", "created_at", "updated_at", "version")),
            // V2: inspect_result · version · updated_at (V2 ALTER 순서)
            Map.entry("maintenance_record", List.of("id", "shop_id", "vehicle_id", "service_item_id", "visit_key",
                "record_date", "mileage", "record_type", "source", "staff_id", "memo", "created_at",
                "inspect_result", "version", "updated_at")),
            Map.entry("reservation", List.of("id", "shop_id", "vehicle_id", "reserved_at", "status", "purpose_other",
                "purpose_memo", "assignee_staff_id", "created_by_staff_id", "version", "created_at", "updated_at")),
            Map.entry("reservation_item", List.of("id", "shop_id", "reservation_id", "service_item_id", "created_at")),
            // V2 이력 표 (07 4.1·4.2)
            Map.entry("maintenance_record_revision", List.of("id", "shop_id", "record_id", "revision_no",
                "changed_by_staff_id", "reason", "prev_record_date", "prev_mileage", "prev_record_type",
                "prev_service_item_id", "prev_inspect_result", "prev_memo", "prev_staff_id", "created_at")),
            Map.entry("reservation_revision", List.of("id", "shop_id", "reservation_id", "kind",
                "changed_by_staff_id", "reason", "prev_status", "new_status", "prev_memo", "new_memo",
                "prev_assignee_id", "new_assignee_id", "created_at")));

        // 1) 칼럼 목록 전체가 07 3장·4장(V1 + V2)과 같다
        for (String table : TABLES) {
            assertThat(columnsOf(table)).as("columns of %s", table).isEqualTo(expectedColumns.get(table));
        }

        // 2) 반드시 있어야 할 칼럼 (V1에 들어간 D-51·D-57 담당 정비사)
        Map<String, List<String>> mustExist = Map.of(
            "reservation", List.of("assignee_staff_id", "created_by_staff_id"),
            "maintenance_record", List.of("staff_id"));
        mustExist.forEach((table, columns) ->
            assertThat(columnsOf(table)).as("must exist in %s", table).containsAll(columns));

        // 3) 없어야 할 표 (V3 연락 기록) — 07 4장. V2 이력 표 2개는 1)의 칼럼 목록과 V1-1의 테이블 목록이 확인한다
        for (String table : List.of("contact_log")) {
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table)).as("table %s must not exist before V3", table).isZero();
        }

        // 4) 없어야 할 칼럼 (V3: P1) — 07 4장
        Map<String, List<String>> mustNotExist = Map.of(
            "customer", List.of("status", "anonymized_at"),
            "vehicle", List.of("active"),
            "staff", List.of("active"));
        mustNotExist.forEach((table, columns) ->
            assertThat(columnsOf(table)).as("must not exist in %s", table).doesNotContainAnyElementsOf(columns));

        // 5) 예약 상태 CHECK: V2에서 IN_PROGRESS·NO_SHOW가 더해졌다 (같은 이름으로 다시 만듦)
        assertThat(checkClause("ck_reservation_status"))
            .contains("RESERVED", "COMPLETED", "CANCELED", "IN_PROGRESS", "NO_SHOW");

        // 6) V2 CHECK 이름 (회의 결정 5): 점검 결과 값, 점검 결과는 INSPECT일 때만, 예약 이력 kind
        assertThat(checkClause("ck_maintenance_record_inspect_result")).contains("GOOD", "CAUTION");
        assertThat(checkClause("ck_maintenance_record_inspect_result_record_type")).contains("INSPECT");
        assertThat(checkClause("ck_reservation_revision_kind")).contains("STATUS", "MEMO", "ASSIGNEE");
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
        // created_at 11개(표 11개) + updated_at 4개 (customer · vehicle · reservation · maintenance_record(V2))
        assertThat(timestampColumns).isEqualTo(15);
    }

    // ---------- helpers ----------

    private long insertStaff(long shopId, String loginId, String role) {
        return insert("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)", shopId, loginId, "{noop}x", "검증 " + role, role, NOW);
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
            "SELECT column_name AS c FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
            String.class, table);
    }

    private String checkClause(String constraintName) {
        return jdbc.queryForObject(
            "SELECT check_clause FROM information_schema.check_constraints"
                + " WHERE constraint_schema = DATABASE() AND constraint_name = ?",
            String.class, constraintName);
    }

    /** 인덱스 칼럼을 순서대로 쉼표로 이어 돌려준다. 인덱스가 없으면 null. */
    private String indexColumns(String table, String indexName) {
        return jdbc.queryForObject(
            "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
            String.class, table, indexName);
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

    /** 오류 번호와 함께, 어느 제약이 막았는지(MySQL 메시지의 제약 이름)까지 확인한다. */
    private void assertRejectedBy(int expectedErrorCode, String constraintName, String sql, Object... args) {
        Throwable thrown = catchThrowable(() -> jdbc.update(sql, args));
        assertThat(thrown).as("should be rejected by %s: %s", constraintName, sql).isNotNull();
        assertThat(mysqlErrorCode(thrown)).as("MySQL error code for: %s (%s)", sql, thrown.getMessage())
            .isEqualTo(expectedErrorCode);
        // FK(1452)는 `이름`, CHECK(3819)는 '이름' 으로 메시지에 나온다
        assertThat(mysqlMessage(thrown)).as("violated constraint for: %s", sql)
            .containsAnyOf("`" + constraintName + "`", "'" + constraintName + "'");
    }

    private static String mysqlMessage(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException e && e.getErrorCode() != 0) {
                return e.getMessage();
            }
        }
        return "";
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
