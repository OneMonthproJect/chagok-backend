package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.maintenance.api.VisitRef;
import com.wanted.chagok.domain.maintenance.dto.RecordEditRequest;
import com.wanted.chagok.domain.maintenance.dto.RecordEditResponse;
import com.wanted.chagok.domain.maintenance.dto.RecordRevisionListResponse;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest.RecordInput;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
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
 * 정비 기록 수정(API 37)·수정 이력(API 38, F-MNT-04): Entity ↔ V2 {@code validate}·CHECK·복합 FK, {@code version} 증가,
 * 이력 1행, 정비소 격리(X14), 차량 주행거리 반영(POL-60), API 11 {@code version}·{@code edited}·{@code staff}.
 * 권한·기간 경계는 {@code MaintenanceEditServiceTest}. 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}).
 * 테스트 트랜잭션은 끝나면 롤백한다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class MaintenanceEditDbTest {

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
    private long ownerA;
    private long mechanicA;
    private long mechanicA2;
    private long ownerB;
    private long vehicleA;
    private long oil;
    private long filter;
    private LocalDate today;

    @BeforeEach
    void data() {
        today = LocalDate.now(clock);
        shopA = insertShop("A정비소", "02-000-0001");
        shopB = insertShop("B정비소", "02-000-0002");
        ownerA = insertStaff(shopA, "a-owner", "박사장", "OWNER");
        mechanicA = insertStaff(shopA, "a-mech", "김정비", "MECHANIC");
        mechanicA2 = insertStaff(shopA, "a-mech2", "이정비", "MECHANIC");
        ownerB = insertStaff(shopB, "b-owner", "최사장", "OWNER");
        long customerA = insertCustomer(shopA, "김민수", "010-0000-0001");
        vehicleA = insertVehicle(shopA, customerA, "12가0001", 2018, 50_000);
        oil = insertItem(shopA, "ENGINE_OIL", "엔진오일", 1);
        filter = insertItem(shopA, "AIR_CLEANER", "에어클리너", 2);
    }

    private LoginStaff owner() {
        return new LoginStaff(ownerA, shopA, StaffRole.OWNER);
    }

    private LoginStaff mechanic() {
        return new LoginStaff(mechanicA, shopA, StaffRole.MECHANIC);
    }

    /** 담당 mechanicA가 어제 날짜로 저장한 교체·점검 두 건의 방문. 반환은 교체(엔진오일) 기록 id. */
    private long savedRecord() {
        service.saveVisit(shopA, mechanicA, vehicleA, new VisitSaveRequest(today.minusDays(1), 50_000, null, null,
            List.of(new RecordInput(oil, RecordType.REPLACE, null, null, null, null),
                new RecordInput(filter, RecordType.INSPECT, null, null, null, null))));
        em.flush();
        em.clear();
        return jdbc.queryForObject("SELECT id FROM maintenance_record WHERE vehicle_id = ? AND service_item_id = ?",
            Long.class, vehicleA, oil);
    }

    private static RecordEditRequest memo(int version, String memo, String reason) {
        return new RecordEditRequest(version, null, null, null, null, null, memo, null, reason);
    }

    private int versionOf(long recordId) {
        return jdbc.queryForObject("SELECT version FROM maintenance_record WHERE id = ?", Integer.class, recordId);
    }

    @Test
    @DisplayName("H1: 수정하면 version이 1 올라가고 수정 전 값이 이력 1행(수정자·사유 포함)으로 남는다. 두 번째는 번호 2")
    void edit_bumpsVersion_andWritesRevisionRow() {
        long recordId = savedRecord();
        assertThat(versionOf(recordId)).isZero();

        RecordEditResponse first = service.editRecord(owner(), recordId, memo(0, "합성유 사용", "입력 누락 보완"));
        em.flush();
        em.clear();

        assertThat(first.version()).isEqualTo(1);
        assertThat(versionOf(recordId)).isEqualTo(1);
        Map<String, Object> rev = jdbc.queryForMap("SELECT * FROM maintenance_record_revision WHERE record_id = ?", recordId);
        assertThat(((Number) rev.get("revision_no")).intValue()).isEqualTo(1);
        assertThat(((Number) rev.get("changed_by_staff_id")).longValue()).isEqualTo(ownerA);
        assertThat(rev.get("reason")).isEqualTo("입력 누락 보완");
        assertThat(rev.get("prev_memo")).isNull();
        assertThat(rev.get("prev_record_type")).isEqualTo("REPLACE");
        assertThat(((Number) rev.get("prev_staff_id")).longValue()).isEqualTo(mechanicA);
        assertThat(rev.get("created_at")).isNotNull();

        RecordEditResponse second = service.editRecord(owner(), recordId, memo(1, "다시 수정", null));
        em.flush();
        assertThat(second.version()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT revision_no FROM maintenance_record_revision WHERE record_id = ? ORDER BY revision_no",
            Integer.class, recordId)).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT memo FROM maintenance_record WHERE id = ?", String.class, recordId))
            .isEqualTo("다시 수정");
    }

    @Test
    @DisplayName("H4: 낡은 version으로 보내면 409이고 기록·이력이 그대로다")
    void edit_staleVersion_conflict_andNothingSaved() {
        long recordId = savedRecord();
        service.editRecord(owner(), recordId, memo(0, "먼저 수정", null));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.editRecord(owner(), recordId, memo(0, "낡은 화면", null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));

        assertThat(jdbc.queryForObject("SELECT memo FROM maintenance_record WHERE id = ?", String.class, recordId))
            .isEqualTo("먼저 수정");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record_revision WHERE record_id = ?", Long.class, recordId))
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("H5·X14: 다른 정비소 사장은 수정도 이력 조회도 404")
    void otherShop_notFound() {
        long recordId = savedRecord();
        LoginStaff otherOwner = new LoginStaff(ownerB, shopB, StaffRole.OWNER);

        assertThatThrownBy(() -> service.editRecord(otherOwner, recordId, memo(0, "남의 기록", null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.revisions(shopB, recordId))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(versionOf(recordId)).isZero();
    }

    @Test
    @DisplayName("H2·H7: 방문일·구분(점검 결과 CHECK)·항목·담당을 바꿔 저장되고, 같은 방문에 같은 항목이 둘이 되면 400")
    void edit_changesColumns_andDuplicateItemRejected() {
        long recordId = savedRecord();

        // 이미 같은 방문에 에어클리너가 있으므로 엔진오일 → 에어클리너는 400, 저장 안 됨
        assertThatThrownBy(() -> service.editRecord(owner(), recordId,
            new RecordEditRequest(0, null, null, null, filter, null, null, null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT));

        service.editRecord(owner(), recordId, new RecordEditRequest(0, today.minusDays(5), null, RecordType.INSPECT, null,
            "CAUTION", null, mechanicA2, null));
        em.flush();
        em.clear();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM maintenance_record WHERE id = ?", recordId);
        assertThat(row.get("record_date").toString()).isEqualTo(today.minusDays(5).toString());
        assertThat(row.get("record_type")).isEqualTo("INSPECT");
        assertThat(row.get("inspect_result")).isEqualTo("CAUTION");
        assertThat(row.get("source")).isEqualTo("SHOP");
        assertThat(((Number) row.get("staff_id")).longValue()).isEqualTo(mechanicA2);
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    @DisplayName("POL-60: 수정한 주행거리가 차량 최근 주행거리보다 크면 갱신하고, 작으면 갱신하지 않는다")
    void edit_mileage_updatesVehicleOnlyWhenLarger() {
        long recordId = savedRecord();

        service.editRecord(owner(), recordId, new RecordEditRequest(0, null, 60_000, null, null, null, null, null, null));
        em.flush();
        assertThat(jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicleA))
            .isEqualTo(60_000);

        service.editRecord(owner(), recordId, new RecordEditRequest(1, null, 40_000, null, null, null, null, null, null));
        em.flush();
        assertThat(jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicleA))
            .isEqualTo(60_000);
        assertThat(jdbc.queryForObject("SELECT mileage FROM maintenance_record WHERE id = ?", Integer.class, recordId))
            .isEqualTo(40_000);
    }

    @Test
    @DisplayName("38: 두 번 수정한 뒤 이력은 번호 순으로, 바뀐 필드만 항목명 전·후로 나온다")
    void revisions_afterTwoEdits() {
        long recordId = savedRecord();
        service.editRecord(owner(), recordId, new RecordEditRequest(0, null, 52_000, null, null, null, "합성유 사용", null, "입력 누락 보완"));
        em.flush();
        service.editRecord(owner(), recordId, new RecordEditRequest(1, null, null, RecordType.INSPECT, null, "CAUTION", null, null, null));
        em.flush();
        em.clear();

        RecordRevisionListResponse res = service.revisions(shopA, recordId);

        assertThat(res.items()).hasSize(2);
        RecordRevisionListResponse.Item first = res.items().get(0);
        assertThat(first.revisionNo()).isEqualTo(1);
        assertThat(first.changedBy()).isEqualTo(new RecordRevisionListResponse.Person(ownerA, "박사장"));
        assertThat(first.reason()).isEqualTo("입력 누락 보완");
        assertThat(first.changes()).containsExactly(
            new RecordRevisionListResponse.Change("mileage", 50_000, 52_000),
            new RecordRevisionListResponse.Change("memo", null, "합성유 사용"));
        assertThat(res.items().get(1).changes()).containsExactly(
            new RecordRevisionListResponse.Change("type", "REPLACE", "INSPECT"),
            new RecordRevisionListResponse.Change("inspectResult", null, "CAUTION"));
        assertThat(service.revisions(shopA, jdbc.queryForObject(
            "SELECT id FROM maintenance_record WHERE vehicle_id = ? AND service_item_id = ?", Long.class, vehicleA, filter)).items())
            .isEmpty();
    }

    @Test
    @DisplayName("11: 모든 기록에 version·edited·staff가 붙고, 수정하면 version이 오르고 edited가 true가 된다")
    void list_showsVersionEditedStaff() {
        long recordId = savedRecord();

        VisitRef.VisitRecord before = recordOf(recordId);
        assertThat(before.version()).isZero();
        assertThat(before.edited()).isFalse();
        assertThat(before.staff()).isEqualTo(new VisitRef.RecordStaff(mechanicA, "김정비"));

        service.editRecord(owner(), recordId, new RecordEditRequest(0, null, null, null, null, null, "메모", mechanicA2, null));
        em.flush();
        em.clear();

        VisitRef.VisitRecord after = recordOf(recordId);
        assertThat(after.version()).isEqualTo(1);
        assertThat(after.edited()).isTrue();
        assertThat(after.staff()).isEqualTo(new VisitRef.RecordStaff(mechanicA2, "이정비"));
        // 08 11: 묶음의 staffName은 담당을 고쳐도 바뀌지 않는다(처음 담당 그대로)
        assertThat(service.listVisits(shopA, vehicleA, PageLimit.of(null, null)).items().get(0).staffName())
            .isEqualTo("김정비");
        // 수정하지 않은 같은 방문의 다른 기록은 그대로
        VisitRef.VisitRecord other = service.listVisits(shopA, vehicleA, PageLimit.of(null, null)).items().get(0).records().stream()
            .filter(r -> r.recordId() != recordId).findFirst().orElseThrow();
        assertThat(other.edited()).isFalse();
        assertThat(other.version()).isZero();
    }

    private VisitRef.VisitRecord recordOf(long recordId) {
        return service.listVisits(shopA, vehicleA, PageLimit.of(null, null)).items().stream()
            .flatMap(v -> v.records().stream()).filter(r -> r.recordId() == recordId).findFirst().orElseThrow();
    }

    // ---------- 테스트 데이터 ----------

    private long insertShop(String name, String phone) {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES (?, ?, 'ACTIVE', NOW())", name, phone);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertStaff(long shopId, String loginId, String name, String role) {
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, ?, 'x', ?, ?, NOW())",
            shopId, loginId, name, role);
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
