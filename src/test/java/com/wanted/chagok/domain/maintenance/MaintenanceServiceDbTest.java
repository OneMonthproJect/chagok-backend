package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.domain.maintenance.api.VisitRef;
import com.wanted.chagok.domain.maintenance.dto.VisitListResponse;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.web.PageLimit;
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
 * 방문 이력 조회(API 11)와 MaintenanceReader 구현: Entity ↔ V1 {@code ddl-auto: validate}(CHAR(36) visit_key·VARCHAR 구분 포함),
 * 방문 묶음·정렬, 정비소 격리(X2). 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test).
 * 테스트 트랜잭션은 끝나면 롤백한다. 정비소·차량·항목·기록은 이 테스트가 직접 넣는다. 저장(API 12)의 정합성은 #42에서 더한다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class MaintenanceServiceDbTest {

    private static final String KEY_OLD = "00000000-0000-4000-8000-000000000001";
    private static final String KEY_NEW = "00000000-0000-4000-8000-000000000002";
    private static final String KEY_EXT = "00000000-0000-4000-8000-000000000003";
    private static final String KEY_B = "00000000-0000-4000-8000-0000000000bb";

    @Autowired
    private MaintenanceService service;

    @Autowired
    private MaintenanceReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    private long shopA;
    private long shopB;
    private long vehicleA;
    private long vehicleB;
    private long oil;
    private long filter;
    private long staffA;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소", "02-000-0001");
        shopB = insertShop("B정비소", "02-000-0002");
        staffA = insertStaff(shopA, "a-mech");
        long staffB = insertStaff(shopB, "b-mech");
        vehicleA = insertVehicle(shopA, insertCustomer(shopA, "김민수", "010-0000-0001"), "12가0001");
        vehicleB = insertVehicle(shopB, insertCustomer(shopB, "박지성", "010-0000-0002"), "34나0002");
        oil = insertItem(shopA, "ENGINE_OIL", "엔진오일", 1);
        filter = insertItem(shopA, "AIR_CLEANER", "에어클리너", 2);
        long oilB = insertItem(shopB, "ENGINE_OIL", "엔진오일", 1);

        insertRecord(shopA, vehicleA, oil, KEY_OLD, "2026-05-14", 10_000, "REPLACE", "SHOP", staffA, "2026-05-14 10:00:00", null);
        insertRecord(shopA, vehicleA, oil, KEY_NEW, "2026-10-24", null, "REPLACE", "SHOP", staffA, "2026-10-24 10:00:00", "교체");
        insertRecord(shopA, vehicleA, filter, KEY_NEW, "2026-10-24", null, "INSPECT", "SHOP", staffA, "2026-10-24 10:00:00", null);
        insertRecord(shopA, vehicleA, filter, KEY_EXT, "2026-01-02", null, "EXTERNAL", "CUSTOMER_STATEMENT", staffA, "2026-11-01 09:00:00", null);
        insertRecord(shopB, vehicleB, oilB, KEY_B, "2026-10-30", 5_000, "DECLINE", "SHOP", staffB, "2026-10-30 10:00:00", null);
    }

    @Test
    @DisplayName("API 11: 방문 묶음을 최신순으로, 외부만 있는 방문은 그 날짜로, 값이 그대로 읽힌다(Entity validate 포함)")
    void listVisits_groupedNewestFirst() {
        VisitListResponse result = service.listVisits(shopA, vehicleA, PageLimit.of(null, null));

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.items()).extracting(VisitRef::visitKey).containsExactly(KEY_NEW, KEY_OLD, KEY_EXT);
        VisitRef latest = result.items().get(0);
        assertThat(latest.visitDate()).isEqualTo(LocalDate.parse("2026-10-24"));
        assertThat(latest.staffName()).isEqualTo("김정비");
        assertThat(latest.records()).hasSize(2);
        assertThat(latest.records().get(0).itemName()).isEqualTo("엔진오일");
        assertThat(latest.records().get(0).type()).isEqualTo("REPLACE");
        assertThat(latest.records().get(0).memo()).isEqualTo("교체");
        assertThat(result.items().get(1).mileage()).isEqualTo(10_000);
        assertThat(result.items().get(2).visitDate()).isEqualTo(LocalDate.parse("2026-01-02"));
    }

    @Test
    @DisplayName("API 11·17(#110 PR-B): source·inspectResult가 DB 값 그대로 — 점검 CAUTION, 교체는 null, 외부는 CUSTOMER_STATEMENT")
    void listVisits_carriesSourceAndInspectResult() {
        jdbc.update("UPDATE maintenance_record SET inspect_result = 'CAUTION' WHERE visit_key = ? AND record_type = 'INSPECT'", KEY_NEW);
        // 옛 방문에 양호(GOOD) 점검 한 건을 더한다
        insertRecord(shopA, vehicleA, filter, KEY_OLD, "2026-05-14", null, "INSPECT", "SHOP", staffA, "2026-05-14 10:00:00", null);
        jdbc.update("UPDATE maintenance_record SET inspect_result = 'GOOD' WHERE visit_key = ? AND record_type = 'INSPECT'", KEY_OLD);

        VisitListResponse result = service.listVisits(shopA, vehicleA, PageLimit.of(null, null));

        VisitRef latest = result.items().get(0);
        assertThat(latest.records()).extracting(VisitRef.VisitRecord::type).containsExactly("REPLACE", "INSPECT");
        assertThat(latest.records()).extracting(VisitRef.VisitRecord::source).containsExactly("SHOP", "SHOP");
        assertThat(latest.records()).extracting(VisitRef.VisitRecord::inspectResult).containsExactly(null, "CAUTION");
        VisitRef old = result.items().get(1);
        assertThat(old.records()).extracting(VisitRef.VisitRecord::type).containsExactly("REPLACE", "INSPECT");
        assertThat(old.records()).extracting(VisitRef.VisitRecord::inspectResult).containsExactly(null, "GOOD");
        VisitRef external = result.items().get(2);
        assertThat(external.records()).extracting(VisitRef.VisitRecord::source).containsExactly("CUSTOMER_STATEMENT");
        assertThat(external.records()).extracting(VisitRef.VisitRecord::inspectResult).containsExactly((String) null);
        // 17의 recentVisits도 같은 매핑(toRefs)을 쓴다
        assertThat(service.findRecentVisits(shopA, vehicleA, 2).get(0).records())
            .extracting(VisitRef.VisitRecord::inspectResult).containsExactly(null, "CAUTION");
    }

    @Test
    @DisplayName("X2: 남의 정비소 차량·없는 차량은 404 NOT_FOUND")
    void listVisits_notOwnedVehicle() {
        for (long vehicle : new long[] {vehicleB, 999_999L}) {
            assertThatThrownBy(() -> service.listVisits(shopA, vehicle, PageLimit.of(null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    @Test
    @DisplayName("MaintenanceReader: 정비소가 다르면 기록이 섞이지 않고, 기록 없는 차량도 키가 있다")
    void reader_isolatesShopAndKeepsEmptyKeys() {
        Map<Long, List<MaintenanceRecordRef>> result = reader.findRecordsByVehicle(shopA, List.of(vehicleA, vehicleB));

        assertThat(result.get(vehicleA)).hasSize(4);
        assertThat(result.get(vehicleB)).isEmpty();
        assertThat(result.get(vehicleA)).extracting(MaintenanceRecordRef::resetsCycle)
            .containsExactly(true, true, true, false);
        assertThat(reader.countVisits(shopA, vehicleA)).isEqualTo(2);
        assertThat(reader.countVisits(shopA, vehicleB)).isZero();
        assertThat(reader.findRecentVisits(shopA, vehicleA, 1)).extracting(VisitRef::visitKey).containsExactly(KEY_NEW);
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

    private long insertVehicle(long shopId, long customerId, String plate) {
        jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, model, created_at, updated_at) VALUES (?, ?, ?, '아반떼', NOW(), NOW())",
            shopId, customerId, plate);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertItem(long shopId, String code, String name, int sortOrder) {
        jdbc.update("INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at) VALUES (?, ?, ?, 6, 10000, TRUE, ?, NOW())",
            shopId, code, name, sortOrder);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertRecord(long shopId, long vehicleId, long itemId, String visitKey, String date, Integer mileage,
        String type, String source, long staffId, String createdAt, String memo) {
        jdbc.update("INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            // V2부터 updated_at NOT NULL (DEFAULT 없음): 새 기록이라 created_at과 같은 값
            shopId, vehicleId, itemId, visitKey, date, mileage, type, source, staffId, memo, createdAt, createdAt);
    }
}
