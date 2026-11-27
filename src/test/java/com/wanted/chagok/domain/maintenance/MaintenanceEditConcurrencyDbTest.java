package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanted.chagok.domain.maintenance.dto.RecordEditRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest.RecordInput;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 정비 기록 수정(API 37)을 두 요청이 동시에 보낼 때: 한 쪽만 성공하고 다른 쪽은 서버 오류(500)가 아니라 업무 오류로 막히는지 본다.
 * 두 스레드가 같은 순간에 시작하도록 맞추지만 실행 순서는 보장되지 않는다 — 겹치지 않고 차례로 실행되면 같은 결과(한 쪽 거절)가 나와
 * 통과한다. 겹치면 서비스의 잠금·{@code version} 확인이 실제로 막는 것을 증명한다(잠금을 빼면 겹칠 때 이 테스트가 실패한다).
 *
 * <p>스레드마다 별도 트랜잭션이어야 하므로 클래스에 {@code @Transactional}을 붙이지 않는다. 대신 {@code MaintenanceSaveRollbackDbTest}처럼
 * 넣은 행을 {@code @AfterEach}에서 직접 지운다. 빈 MySQL이 필요하다({@code ./gradlew dbTest}).
 */
@Tag("db")
@SpringBootTest
class MaintenanceEditConcurrencyDbTest {

    @Autowired
    private MaintenanceService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long shop;
    private long owner;
    private long vehicle;
    private long oil;
    private long filter;
    private long tire;
    private long oilRecord;
    private long filterRecord;

    @BeforeEach
    void data() {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES ('동시수정시험정비소', '02-900-0003', 'ACTIVE', NOW())");
        shop = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, 'concurrency-owner', 'x', '박사장', 'OWNER', NOW())", shop);
        owner = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) VALUES (?, '시험고객', '010-9000-0003', FALSE, NOW(), NOW())", shop);
        long customer = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at) VALUES (?, ?, '90가0003', '아반떼', 2018, 50000, NOW(), NOW())",
            shop, customer);
        vehicle = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        oil = insertItem("ENGINE_OIL", "엔진오일", 1);
        filter = insertItem("AIR_CLEANER", "에어클리너", 2);
        tire = insertItem("TIRE", "타이어", 3);

        service.saveVisit(shop, owner, vehicle, new VisitSaveRequest(LocalDate.now(clock).minusDays(1), 50_000, null, null,
            List.of(new RecordInput(oil, RecordType.REPLACE, null, null, null, null),
                new RecordInput(filter, RecordType.INSPECT, null, null, null, null))));
        oilRecord = recordId(oil);
        filterRecord = recordId(filter);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM maintenance_record_revision WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM maintenance_record WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM service_item WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM vehicle WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM customer WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM staff WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM shop WHERE id = ?", shop);
    }

    @Test
    @DisplayName("H7: 같은 방문의 두 기록을 동시에 같은 항목으로 바꿔도 한 쪽만 성공하고 중복 항목이 남지 않는다")
    void concurrentSameItem_onlyOneSucceeds() throws Exception {
        LoginStaff staff = new LoginStaff(owner, shop, StaffRole.OWNER);

        List<Throwable> results = runTogether(
            () -> service.editRecord(staff, oilRecord, itemTo(tire)),
            () -> service.editRecord(staff, filterRecord, itemTo(tire)));

        assertThat(results.stream().filter(t -> t == null)).hasSize(1);
        Throwable failed = results.stream().filter(t -> t != null).findFirst().orElseThrow();
        assertThat(failed).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record WHERE shop_id = ? AND service_item_id = ?",
            Long.class, shop, tire)).isEqualTo(1L);
    }

    @Test
    @DisplayName("H4: 같은 기록을 같은 version으로 동시에 고치면 한 쪽만 성공하고, 다른 쪽은 409(서버 오류 아님)이며 이력은 1건이다")
    void concurrentSameVersion_oneConflicts() throws Exception {
        LoginStaff staff = new LoginStaff(owner, shop, StaffRole.OWNER);

        List<Throwable> results = runTogether(
            () -> service.editRecord(staff, oilRecord, memo("첫째 화면")),
            () -> service.editRecord(staff, oilRecord, memo("둘째 화면")));

        assertThat(results.stream().filter(t -> t == null)).hasSize(1);
        Throwable failed = results.stream().filter(t -> t != null).findFirst().orElseThrow();
        // 서비스가 version을 직접 비교해 던지거나, 저장 때 JPA @Version이 던진다. 둘 다 전역 처리기에서 409가 된다.
        boolean conflict = failed instanceof OptimisticLockingFailureException
            || (failed instanceof BusinessException e && e.code() == ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(conflict).as("실패한 쪽의 예외: %s", failed).isTrue();
        assertThat(jdbc.queryForObject("SELECT version FROM maintenance_record WHERE id = ?", Integer.class, oilRecord))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record_revision WHERE record_id = ?", Long.class,
            oilRecord)).isEqualTo(1L);
    }

    private RecordEditRequest itemTo(long itemId) {
        return new RecordEditRequest(0, null, null, null, itemId, null, null, null, null);
    }

    private RecordEditRequest memo(String memo) {
        return new RecordEditRequest(0, null, null, null, null, null, memo, null, null);
    }

    /** 두 호출을 같은 순간에 시작한다. 각 결과는 성공이면 null, 실패면 던져진 예외. */
    private static List<Throwable> runTogether(Runnable first, Runnable second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable call : List.of(first, second)) {
                Callable<Throwable> task = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        call.run();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                };
                futures.add(pool.submit(task));
            }
            List<Throwable> results = new ArrayList<>();
            for (Future<Throwable> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long recordId(long itemId) {
        return jdbc.queryForObject("SELECT id FROM maintenance_record WHERE vehicle_id = ? AND service_item_id = ?",
            Long.class, vehicle, itemId);
    }

    private long insertItem(String code, String name, int sortOrder) {
        jdbc.update("INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at) VALUES (?, ?, ?, 6, 10000, TRUE, ?, NOW())",
            shop, code, name, sortOrder);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
