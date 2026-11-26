package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.maintenance.api.VisitRef;
import com.wanted.chagok.domain.maintenance.dto.VisitListResponse;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest.RecordInput;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest.VehicleInput;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveResponse;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.web.PageLimit;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문·정비 기록 저장(API 12): Entity ↔ V1 {@code ddl-auto: validate}·CHECK 제약, 한 방문 묶음, 차량 반영, 정비소 격리(X5)·쓰는 항목(X11),
 * 저장 후 API 11 이력에 나타나는지. 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test).
 * 테스트 트랜잭션은 끝나면 롤백한다. 트랜잭션 롤백 자체(저장 중 실패)는 클래스에 {@code @Transactional}이 없는
 * {@code MaintenanceSaveRollbackDbTest}에서 본다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class MaintenanceSaveDbTest {

    @Autowired
    private MaintenanceService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private Clock clock;

    private long shopA;
    private long shopB;
    private long staffA;
    private long vehicleA;
    private long vehicleB;
    private long emptyVehicleA;
    private long oil;
    private long filter;
    private long oilOfB;
    private LocalDate today;

    @BeforeEach
    void data() {
        today = LocalDate.now(clock);
        shopA = insertShop("A정비소", "02-000-0001");
        shopB = insertShop("B정비소", "02-000-0002");
        staffA = insertStaff(shopA, "a-mech");
        insertStaff(shopB, "b-mech");
        long customerA = insertCustomer(shopA, "김민수", "010-0000-0001");
        long customerB = insertCustomer(shopB, "박지성", "010-0000-0002");
        vehicleA = insertVehicle(shopA, customerA, "12가0001", 2018, 50_000);
        emptyVehicleA = insertVehicle(shopA, customerA, "12가0002", null, null);
        vehicleB = insertVehicle(shopB, customerB, "34나0003", 2018, 50_000);
        oil = insertItem(shopA, "ENGINE_OIL", "엔진오일", 1);
        filter = insertItem(shopA, "AIR_CLEANER", "에어클리너", 2);
        oilOfB = insertItem(shopB, "ENGINE_OIL", "엔진오일", 1);
    }

    private static RecordInput rec(long itemId, RecordType type, String memo) {
        return new RecordInput(itemId, type, memo, null, null, null);
    }

    private static VisitSaveRequest request(LocalDate date, Integer mileage, Integer modelYear, RecordInput... rows) {
        return new VisitSaveRequest(date, mileage, modelYear == null ? null : new VehicleInput(modelYear), null,
            List.of(rows));
    }

    private long count(String table, long vehicleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE vehicle_id = ?", Long.class, vehicleId);
    }

    @Test
    @DisplayName("저장: 한 visit_key로 행이 저장되고(Entity validate·CHECK 포함) 담당은 저장한 계정, 차량 주행거리가 갱신되며 API 11에 나타난다")
    void saveThenListed() {
        VisitSaveResponse response = service.saveVisit(shopA, staffA, vehicleA,
            request(today, 55_000, null, rec(oil, RecordType.REPLACE, "교체"), rec(filter, RecordType.INSPECT, null)));
        em.flush();

        assertThat(response.savedCount()).isEqualTo(2);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at "
                + "FROM maintenance_record WHERE vehicle_id = ? ORDER BY id", vehicleA);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("visit_key")).containsOnly(response.visitKey());
        assertThat(rows).extracting(r -> r.get("source")).containsOnly("SHOP");
        assertThat(rows).extracting(r -> ((Number) r.get("staff_id")).longValue()).containsOnly(staffA);
        assertThat(rows).extracting(r -> r.get("record_type")).containsExactly("REPLACE", "INSPECT");
        assertThat(rows).extracting(r -> r.get("memo")).containsExactly("교체", null);
        assertThat(rows).extracting(r -> r.get("created_at")).doesNotContainNull();
        assertThat(jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicleA))
            .isEqualTo(55_000);

        VisitListResponse list = service.listVisits(shopA, vehicleA, PageLimit.of(null, null));
        assertThat(list.items()).hasSize(1);
        VisitRef visit = list.items().get(0);
        assertThat(visit.visitKey()).isEqualTo(response.visitKey());
        assertThat(visit.visitDate()).isEqualTo(today);
        assertThat(visit.mileage()).isEqualTo(55_000);
        assertThat(visit.staffName()).isEqualTo("김정비");
        assertThat(visit.records()).extracting(VisitRef.VisitRecord::itemName).containsExactly("엔진오일", "에어클리너");
    }

    @Test
    @DisplayName("D-58: 비어 있는 차량은 요청의 연식·주행거리로 채워지고, 없으면 400이며 아무것도 저장하지 않는다")
    void emptyVehicleIsFilled() {
        assertThatThrownBy(() -> service.saveVisit(shopA, staffA, emptyVehicleA,
            request(today, null, null, rec(oil, RecordType.REPLACE, null))))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(count("maintenance_record", emptyVehicleA)).isZero();

        service.saveVisit(shopA, staffA, emptyVehicleA, request(today, 30_000, 2020, rec(oil, RecordType.REPLACE, null)));
        em.flush();

        Map<String, Object> vehicle = jdbc.queryForMap("SELECT model_year, latest_mileage FROM vehicle WHERE id = ?",
            emptyVehicleA);
        assertThat(vehicle).containsEntry("model_year", 2020).containsEntry("latest_mileage", 30_000);
    }

    @Test
    @DisplayName("주행거리가 차량의 최근 값보다 작으면 기록은 저장하되 차량은 갱신하지 않는다 (POL-60)")
    void smallerMileageDoesNotLowerVehicle() {
        service.saveVisit(shopA, staffA, vehicleA, request(today, 10_000, null, rec(oil, RecordType.REPLACE, null)));
        em.flush();

        assertThat(jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicleA))
            .isEqualTo(50_000);
        assertThat(jdbc.queryForObject("SELECT mileage FROM maintenance_record WHERE vehicle_id = ?", Integer.class,
            vehicleA)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("외부·거절·점검 결과·담당 지정이 저장된다 (Entity validate·CHECK 포함): 외부는 정비일·고객 진술, 점검 결과는 점검에만")
    void saveExternalDeclineInspectResultAndMechanic() {
        long mechanic = insertStaff(shopA, "a-mech2");
        LocalDate externalDate = today.minusDays(40);

        service.saveVisit(shopA, staffA, vehicleA, new VisitSaveRequest(today, 55_000, null, mechanic, List.of(
            new RecordInput(oil, RecordType.EXTERNAL, "다른 곳에서 교체", externalDate, 48_000, null),
            rec(filter, RecordType.DECLINE, "다음에 하기로 함"),
            new RecordInput(insertItem(shopA, "WIPER", "와이퍼", 3), RecordType.INSPECT, null, null, null, "CAUTION"))));
        em.flush();

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT record_type, mileage, source, staff_id, inspect_result "
                + "FROM maintenance_record WHERE vehicle_id = ? ORDER BY id", vehicleA);
        assertThat(rows).extracting(r -> r.get("record_type")).containsExactly("EXTERNAL", "DECLINE", "INSPECT");
        assertThat(rows).extracting(r -> r.get("source")).containsExactly("CUSTOMER_STATEMENT", "SHOP", "SHOP");
        assertThat(rows).extracting(r -> ((Number) r.get("mileage")).intValue()).containsExactly(48_000, 55_000, 55_000);
        assertThat(rows).extracting(r -> ((Number) r.get("staff_id")).longValue()).containsOnly(mechanic);
        assertThat(rows).extracting(r -> r.get("inspect_result")).containsExactly(null, null, "CAUTION");

        // 날짜는 DB를 JDBC로 직접 읽지 않고 앱의 조회(API 11)로 본다. 직접 읽으면 테스트 JVM 시간대(CI는 UTC)에 따라
        // 하루 어긋나 보이고, 같은 클래스의 saveThenListed처럼 조회 경로로 보면 시간대와 무관하다.
        VisitRef external = service.listVisits(shopA, vehicleA, PageLimit.of(null, null)).items().stream()
            .filter(v -> v.records().stream().anyMatch(r -> r.type().equals("EXTERNAL")))
            .findFirst().orElseThrow();
        // 외부가 아닌 기록이 함께 있으면 방문일은 그 기록의 날짜다 (08 11)
        assertThat(external.visitDate()).isEqualTo(today);
        assertThat(external.records()).extracting(VisitRef.VisitRecord::type, VisitRef.VisitRecord::date)
            .containsExactlyInAnyOrder(
                org.assertj.core.api.Assertions.tuple("EXTERNAL", externalDate),
                org.assertj.core.api.Assertions.tuple("DECLINE", today),
                org.assertj.core.api.Assertions.tuple("INSPECT", today));
    }

    @Test
    @DisplayName("X13: 다른 정비소 직원·없는 직원을 mechanicId로 보내면 400이고 아무것도 저장하지 않는다")
    void mechanicOfOtherShop() {
        long staffOfB = jdbc.queryForObject("SELECT id FROM staff WHERE shop_id = ?", Long.class, shopB);
        for (long mechanic : new long[] {staffOfB, 999_999L}) {
            assertThatThrownBy(() -> service.saveVisit(shopA, staffA, vehicleA, new VisitSaveRequest(today, 55_000,
                null, mechanic, List.of(rec(oil, RecordType.REPLACE, null)))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT));
        }
        em.flush();

        assertThat(count("maintenance_record", vehicleA)).isZero();
    }

    @Test
    @DisplayName("X5: 남의 정비소 차량·없는 차량은 404이고 B 정비소 기록·차량 값은 바뀌지 않는다")
    void notOwnedVehicle() {
        for (long vehicle : new long[] {vehicleB, 999_999L}) {
            assertThatThrownBy(() -> service.saveVisit(shopA, staffA, vehicle,
                request(today, 99_000, 2020, rec(oil, RecordType.REPLACE, null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        em.flush();

        assertThat(count("maintenance_record", vehicleB)).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicleB))
            .isEqualTo(50_000);
    }

    @Test
    @DisplayName("X11: 다른 정비소의 항목·없는 항목은 400이고 아무것도 저장하지 않는다")
    void itemOfOtherShop() {
        for (long item : new long[] {oilOfB, 999_999L}) {
            assertThatThrownBy(() -> service.saveVisit(shopA, staffA, vehicleA,
                request(today, 55_000, null, rec(oil, RecordType.REPLACE, null), rec(item, RecordType.INSPECT, null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT));
        }
        em.flush();

        assertThat(count("maintenance_record", vehicleA)).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicleA))
            .isEqualTo(50_000);
    }

    @Test
    @DisplayName("미래 방문일·같은 항목 중복·정비일 없는 외부 기록은 400이고 아무것도 저장하지 않는다")
    void invalidInputsSaveNothing() {
        assertThatThrownBy(() -> service.saveVisit(shopA, staffA, vehicleA,
            request(today.plusDays(1), 55_000, null, rec(oil, RecordType.REPLACE, null))))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.saveVisit(shopA, staffA, vehicleA,
            request(today, 55_000, null, rec(oil, RecordType.REPLACE, null), rec(oil, RecordType.INSPECT, null))))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.saveVisit(shopA, staffA, vehicleA,
            request(today, 55_000, null, rec(oil, RecordType.EXTERNAL, null))))
            .isInstanceOf(BusinessException.class);
        em.flush();

        assertThat(count("maintenance_record", vehicleA)).isZero();
    }

    private long insertShop(String name, String phone) {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES (?, ?, 'ACTIVE', NOW())", name, phone);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertStaff(long shopId, String loginId) {
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, ?, 'x', '김정비', 'MECHANIC', NOW())",
            shopId, loginId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertCustomer(long shopId, String name, String phone) {
        jdbc.update("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) VALUES (?, ?, ?, FALSE, NOW(), NOW())",
            shopId, name, phone);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertVehicle(long shopId, long customerId, String plate, Integer year, Integer mileage) {
        jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at) VALUES (?, ?, ?, '아반떼', ?, ?, NOW(), NOW())",
            shopId, customerId, plate, year, mileage);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertItem(long shopId, String code, String name, int sortOrder) {
        jdbc.update("INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at) VALUES (?, ?, ?, 6, 10000, TRUE, ?, NOW())",
            shopId, code, name, sortOrder);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
