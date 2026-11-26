package com.wanted.chagok.screen.visit;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest.RecordInput;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.screen.visit.dto.ItemStatusResponse;
import com.wanted.chagok.screen.visit.service.ItemStatusService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 04 F-MNT-01 완료 기준: 교체 저장 후 그 항목이 여유로 바뀌고, 점검 저장은 상태를 바꾸지 않는다(T12). 저장(API 12)과 항목 상태(API 10, #25)를
 * 실제 DB로 이어 본다. 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 테스트 트랜잭션은 끝나면 롤백한다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class VisitSaveItemStatusDbTest {

    @Autowired
    private MaintenanceService maintenance;

    @Autowired
    private ItemStatusService itemStatus;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long shop;
    private long staff;
    private long vehicle;
    private long oil;
    private long filter;
    private LocalDate today;

    @BeforeEach
    void data() {
        today = LocalDate.now(clock);
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES ('A정비소', '02-000-0001', 'ACTIVE', NOW())");
        shop = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, 'a-mech', 'x', '김정비', 'MECHANIC', NOW())", shop);
        staff = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) VALUES (?, '김민수', '010-0000-0001', FALSE, NOW(), NOW())", shop);
        long customer = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at) VALUES (?, ?, '12가0001', '아반떼', 2018, 50000, NOW(), NOW())",
            shop, customer);
        vehicle = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        oil = insertItem("ENGINE_OIL", "엔진오일", 1);
        filter = insertItem("AIR_CLEANER", "에어클리너", 2);
        // 엔진오일은 400일 전에 교체했다(주기 6개월 → 도래), 에어클리너는 기록이 없다(미확인)
        jdbc.update("INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, '00000000-0000-4000-8000-000000000001', ?, 20000, 'REPLACE', 'SHOP', ?, NOW(), NOW())",
            shop, vehicle, oil, today.minusDays(400), staff);
    }

    private ItemStatus statusOf(long itemId) {
        ItemStatusResponse response = itemStatus.getItemStatus(shop, vehicle, null);
        return response.items().stream().filter(i -> i.itemId() == itemId).findFirst().orElseThrow().status();
    }

    @Test
    @DisplayName("T12: 교체를 저장하면 그 항목이 도래에서 여유로 바뀌고, 점검만 저장한 항목은 미확인 그대로다")
    void replaceResetsCycleInspectDoesNot() {
        assertThat(statusOf(oil)).isEqualTo(ItemStatus.DUE);
        assertThat(statusOf(filter)).isEqualTo(ItemStatus.UNKNOWN);

        maintenance.saveVisit(shop, staff, vehicle, new VisitSaveRequest(today, 55_000, null, null, List.of(
            new RecordInput(oil, RecordType.REPLACE, null, null, null, null),
            new RecordInput(filter, RecordType.INSPECT, "확인", null, null, null))));

        assertThat(statusOf(oil)).isEqualTo(ItemStatus.AHEAD);
        assertThat(statusOf(filter)).isEqualTo(ItemStatus.UNKNOWN);
    }

    private long insertItem(String code, String name, int sortOrder) {
        jdbc.update("INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at) VALUES (?, ?, ?, 6, 10000, TRUE, ?, NOW())",
            shop, code, name, sortOrder);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
