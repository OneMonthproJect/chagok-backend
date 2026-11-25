package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest.RecordInput;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 저장 정합성(이슈 #42): 기록 행을 저장하다 DB가 실패하면 같은 트랜잭션에서 반영한 차량 값도 함께 취소되고 행이 하나도 남지 않는다.
 * 실패는 가짜 없이 실제 DB 오류로 만든다 — 메모가 200자를 넘으면(요청 검증을 거치지 않고 서비스를 직접 부르므로) VARCHAR(200)에서
 * 막힌다. 앞의 행은 이미 INSERT된 뒤라 부분 저장이 남는지도 함께 본다. 차량 UPDATE는 flush 전에는 DB에 나가지 않을 수 있어서,
 * 차량 값 롤백은 따로 {@code em.flush()}로 SQL을 보낸 뒤 바깥 트랜잭션을 실패시키는 시험으로 증명한다.
 *
 * <p>다른 DB 테스트와 달리 클래스에 {@code @Transactional}을 붙이지 않는다(붙이면 서비스의 커밋·롤백을 볼 수 없다).
 * 대신 이 테스트가 넣은 행을 {@code @AfterEach}에서 직접 지운다. 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test).
 */
@Tag("db")
@SpringBootTest
class MaintenanceSaveRollbackDbTest {

    @Autowired
    private MaintenanceService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    private long shop;
    private long staff;
    private long vehicle;
    private long oil;
    private long filter;

    @BeforeEach
    void data() {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES ('롤백시험정비소', '02-900-0002', 'ACTIVE', NOW())");
        shop = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, 'rollback-mech', 'x', '김정비', 'MECHANIC', NOW())", shop);
        staff = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) VALUES (?, '시험고객', '010-9000-0002', FALSE, NOW(), NOW())", shop);
        long customer = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, model, created_at, updated_at) VALUES (?, ?, '90가0002', '아반떼', NOW(), NOW())",
            shop, customer);
        vehicle = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        oil = insertItem("ENGINE_OIL", "엔진오일", 1);
        filter = insertItem("AIR_CLEANER", "에어클리너", 2);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM maintenance_record WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM service_item WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM vehicle WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM customer WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM staff WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM shop WHERE id = ?", shop);
    }

    @Test
    @DisplayName("저장 중 DB 오류: 차량의 연식·주행거리도 취소되고 앞서 넣은 행도 남지 않는다")
    void failureRollsBackVehicleAndRows() {
        String tooLong = "가".repeat(201);
        VisitSaveRequest request = new VisitSaveRequest(LocalDate.now(clock), 55_000,
            new VisitSaveRequest.VehicleInput(2019), null, List.of(
                new RecordInput(oil, RecordType.REPLACE, null, null, null, null),
                new RecordInput(filter, RecordType.INSPECT, tooLong, null, null, null)));

        // DB 오류(DataAccessException)여야 한다. 검증 실패(BusinessException)로 던져져도 통과하는 시험이 되지 않게 좁힌다.
        assertThatThrownBy(() -> service.saveVisit(shop, staff, vehicle, request)).isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record WHERE vehicle_id = ?", Long.class, vehicle))
            .isZero();
        Map<String, Object> row = jdbc.queryForMap("SELECT model_year, latest_mileage FROM vehicle WHERE id = ?", vehicle);
        assertThat(row).containsEntry("model_year", null).containsEntry("latest_mileage", null);
    }

    @Test
    @DisplayName("바깥 트랜잭션 참여: 차량 UPDATE와 기록 INSERT가 실제로 DB에 나간 뒤 바깥이 실패해도 둘 다 취소된다")
    void outerFailureAfterFlushRollsBackVehicleAndRows() {
        VisitSaveRequest request = new VisitSaveRequest(LocalDate.now(clock), 55_000,
            new VisitSaveRequest.VehicleInput(2019), null, List.of(
                new RecordInput(oil, RecordType.REPLACE, null, null, null, null),
                new RecordInput(filter, RecordType.INSPECT, "확인", null, null, null)));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.saveVisit(shop, staff, vehicle, request);
            em.flush();
            // 같은 트랜잭션(같은 연결)에서는 방금 보낸 SQL의 결과가 보인다 — 롤백할 대상이 실제로 있었다는 증거
            assertThat(jdbc.queryForObject("SELECT latest_mileage FROM vehicle WHERE id = ?", Integer.class, vehicle))
                .isEqualTo(55_000);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record WHERE vehicle_id = ?", Long.class,
                vehicle)).isEqualTo(2);
            throw new IllegalStateException("바깥 트랜잭션 실패를 흉내");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record WHERE vehicle_id = ?", Long.class, vehicle))
            .isZero();
        Map<String, Object> row = jdbc.queryForMap("SELECT model_year, latest_mileage FROM vehicle WHERE id = ?", vehicle);
        assertThat(row).containsEntry("model_year", null).containsEntry("latest_mileage", null);
    }

    @Test
    @DisplayName("정상 저장은 바깥 트랜잭션 없이도 커밋된다 (행과 차량 값이 함께 남는다)")
    void successCommitsRowsAndVehicle() {
        VisitSaveRequest request = new VisitSaveRequest(LocalDate.now(clock), 55_000,
            new VisitSaveRequest.VehicleInput(2019), null, List.of(
                new RecordInput(oil, RecordType.REPLACE, null, null, null, null),
                new RecordInput(filter, RecordType.INSPECT, "확인", null, null, null)));

        service.saveVisit(shop, staff, vehicle, request);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record WHERE vehicle_id = ?", Long.class, vehicle))
            .isEqualTo(2);
        Map<String, Object> row = jdbc.queryForMap("SELECT model_year, latest_mileage FROM vehicle WHERE id = ?", vehicle);
        assertThat(row).containsEntry("model_year", 2019).containsEntry("latest_mileage", 55_000);
    }

    private long insertItem(String code, String name, int sortOrder) {
        jdbc.update("INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at) VALUES (?, ?, ?, 6, 10000, TRUE, ?, NOW())",
            shop, code, name, sortOrder);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
