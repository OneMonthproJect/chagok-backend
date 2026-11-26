package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest.VehicleInput;
import com.wanted.chagok.domain.customer.dto.CustomerUpdateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerUpdateResponse;
import com.wanted.chagok.domain.customer.dto.VehicleUpdateRequest;
import com.wanted.chagok.domain.customer.dto.VehicleUpdateResponse;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 수정(API 22)·차량 수정(API 23): {@code version} 비교·증가, 값이 같을 때 그대로, 방문 기록과의 충돌, 정비소 격리(X12).
 * 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 테스트 트랜잭션은 끝나면 롤백한다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class CustomerVehicleUpdateDbTest {

    @Autowired
    private CustomerService service;

    @Autowired
    private CustomerReader reader;

    @Autowired
    private CustomerWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private Clock clock;

    private long shopA;
    private long shopB;
    private long customerA;
    private long vehicleA;
    private long customerB;
    private long vehicleB;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소", "02-000-0001");
        shopB = insertShop("B정비소", "02-000-0002");
        var a = service.register(shopA, request("김민수", "010-0000-0001", "12가0001"));
        customerA = a.customerId();
        vehicleA = a.vehicleId();
        var b = service.register(shopB, request("박지성", "010-0000-0002", "34나0002"));
        customerB = b.customerId();
        vehicleB = b.vehicleId();
        em.flush();
    }

    private static CustomerCreateRequest request(String name, String phone, String plate) {
        return new CustomerCreateRequest(name, phone, true, new VehicleInput(plate, "아반떼", 2019, 12_000));
    }

    private static CustomerUpdateRequest customerReq(int version, String name, String phone, Boolean consent) {
        return new CustomerUpdateRequest(version, name, phone, consent);
    }

    private static VehicleUpdateRequest vehicleReq(int version, String plate, String model, Integer year, Integer mileage) {
        return new VehicleUpdateRequest(version, plate, model, year, mileage);
    }

    private int customerVersionInDb(long id) {
        return jdbc.queryForObject("SELECT version FROM customer WHERE id = ?", Integer.class, id);
    }

    private int vehicleVersionInDb(long id) {
        return jdbc.queryForObject("SELECT version FROM vehicle WHERE id = ?", Integer.class, id);
    }

    private static void assertErrorCode(Throwable e, ErrorCode expected) {
        assertThat(e).isInstanceOfSatisfying(BusinessException.class, b -> assertThat(b.code()).isEqualTo(expected));
    }

    // ---- 07·09 응답의 version ----

    @Test
    @DisplayName("07·09: 새로 등록한 고객·차량의 version은 0이고 읽기 계약·차량 상세 응답에 실린다")
    void versionsAreRead() {
        assertThat(reader.getOwnedCustomer(shopA, customerA).version()).isZero();
        assertThat(service.getVehicleDetail(shopA, vehicleA).version()).isZero();
    }

    // ---- 22 ----

    @Test
    @DisplayName("22: 보낸 필드만 바뀌고 version이 1 오른다(응답·DB 모두), 안 보낸 필드는 그대로")
    void updateCustomer_changesOnlySentFieldsAndBumpsVersion() {
        CustomerUpdateResponse response = service.updateCustomer(shopA, customerA, customerReq(0, null, null, false));

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.consent()).isFalse();
        assertThat(response.name()).isEqualTo("김민수");
        assertThat(response.phone()).isEqualTo("010-0000-0001");
        assertThat(customerVersionInDb(customerA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT consent FROM customer WHERE id = ?", Boolean.class, customerA)).isFalse();

        // 응답의 version을 다음 수정에 쓴다
        CustomerUpdateResponse second = service.updateCustomer(shopA, customerA, customerReq(1, "김민수2", "010-9999-0001", null));
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.name()).isEqualTo("김민수2");
        assertThat(second.phone()).isEqualTo("010-9999-0001");
        assertThat(customerVersionInDb(customerA)).isEqualTo(2);
    }

    @Test
    @DisplayName("22: 보낸 값이 지금 값과 같으면 200으로 그대로 돌려주고 version은 오르지 않는다")
    void updateCustomer_sameValues_keepsVersion() {
        CustomerUpdateResponse response = service.updateCustomer(shopA, customerA,
            customerReq(0, "김민수", "010-0000-0001", true));

        assertThat(response.version()).isZero();
        assertThat(customerVersionInDb(customerA)).isZero();
    }

    @Test
    @DisplayName("22 X12: version이 서버 값과 다르면(낮아도 높아도 음수여도) 409 CONCURRENT_MODIFICATION이고 아무것도 바꾸지 않는다")
    void updateCustomer_versionMismatch() {
        service.updateCustomer(shopA, customerA, customerReq(0, "김민수2", null, null)); // 이제 서버 version은 1

        for (int stale : new int[] {0, 2, -1}) {
            assertThatThrownBy(() -> service.updateCustomer(shopA, customerA, customerReq(stale, "덮어쓰기", null, false)))
                .satisfies(e -> assertErrorCode(e, ErrorCode.CONCURRENT_MODIFICATION));
        }
        assertThat(customerVersionInDb(customerA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM customer WHERE id = ?", String.class, customerA)).isEqualTo("김민수2");
        assertThat(jdbc.queryForObject("SELECT consent FROM customer WHERE id = ?", Boolean.class, customerA)).isTrue();
    }

    @Test
    @DisplayName("22 X12: 남의 정비소 고객·없는 고객은 version이 맞아도 404이고 바꾸지 않는다 (소유 확인이 version 비교보다 먼저)")
    void updateCustomer_notOwned() {
        for (long customer : new long[] {customerB, 999_999L}) {
            assertThatThrownBy(() -> service.updateCustomer(shopA, customer, customerReq(0, "침범", null, false)))
                .satisfies(e -> assertErrorCode(e, ErrorCode.NOT_FOUND));
        }
        assertThat(jdbc.queryForObject("SELECT name FROM customer WHERE id = ?", String.class, customerB)).isEqualTo("박지성");
        assertThat(customerVersionInDb(customerB)).isZero();
    }

    @Test
    @DisplayName("22 X12: 남의 정비소 고객은 틀린 version을 보내도 409가 아니라 404다 (소유 확인이 먼저라 남의 version이 드러나지 않는다)")
    void updateCustomer_notOwned_wrongVersionStill404() {
        assertThatThrownBy(() -> service.updateCustomer(shopA, customerB, customerReq(99, "침범", null, null)))
            .satisfies(e -> assertErrorCode(e, ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("22: 읽은 뒤 저장 사이에 다른 곳이 먼저 바꾸면 저장 때 충돌이 드러난다 (@Version, 처리기가 409로 바꿈)")
    void updateCustomer_concurrentWriteDetectedOnFlush() {
        jdbc.update("UPDATE customer SET version = version + 1 WHERE id = ?", customerA);

        assertThatThrownBy(() -> service.updateCustomer(shopA, customerA, customerReq(0, "덮어쓰기", null, null)))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("23: 읽은 뒤 저장 사이에 다른 곳이 먼저 바꾸면 저장 때 충돌이 드러난다 (@Version, 처리기가 409로 바꿈)")
    void updateVehicle_concurrentWriteDetectedOnFlush() {
        jdbc.update("UPDATE vehicle SET version = version + 1 WHERE id = ?", vehicleA);

        assertThatThrownBy(() -> service.updateVehicle(shopA, vehicleA, vehicleReq(0, null, "덮어쓰기", null, null)))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // ---- 23 ----

    @Test
    @DisplayName("23 X12: 남의 정비소 차량은 틀린 version을 보내도 409가 아니라 404다")
    void updateVehicle_notOwned_wrongVersionStill404() {
        assertThatThrownBy(() -> service.updateVehicle(shopA, vehicleB, vehicleReq(99, null, "침범", null, null)))
            .satisfies(e -> assertErrorCode(e, ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("23: 보낸 필드만 바뀌고 version이 1 오른다(응답·DB 모두), 고객·안 보낸 필드는 그대로")
    void updateVehicle_changesOnlySentFieldsAndBumpsVersion() {
        VehicleUpdateResponse response = service.updateVehicle(shopA, vehicleA, vehicleReq(0, null, "K5 하이브리드", null, null));

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.model()).isEqualTo("K5 하이브리드");
        assertThat(response.plateNumber()).isEqualTo("12가0001");
        assertThat(response.modelYear()).isEqualTo(2019);
        assertThat(response.latestMileage()).isEqualTo(12_000);
        assertThat(vehicleVersionInDb(vehicleA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM vehicle WHERE id = ?", Long.class, vehicleA)).isEqualTo(customerA);
    }

    @Test
    @DisplayName("23: 수정으로는 최근 주행거리를 지금보다 작게도 고칠 수 있다(방문 기록과 달리)")
    void updateVehicle_mileageCanDecrease() {
        VehicleUpdateResponse response = service.updateVehicle(shopA, vehicleA, vehicleReq(0, null, null, null, 5_000));

        assertThat(response.latestMileage()).isEqualTo(5_000);
        assertThat(response.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("23: 보낸 값이 지금 값과 같으면 200으로 그대로 돌려주고 version은 오르지 않는다")
    void updateVehicle_sameValues_keepsVersion() {
        VehicleUpdateResponse response = service.updateVehicle(shopA, vehicleA,
            vehicleReq(0, "12가0001", "아반떼", 2019, 12_000));

        assertThat(response.version()).isZero();
        assertThat(vehicleVersionInDb(vehicleA)).isZero();
    }

    @Test
    @DisplayName("23 X12: version이 서버 값과 다르면 409 CONCURRENT_MODIFICATION이고 아무것도 바꾸지 않는다")
    void updateVehicle_versionMismatch() {
        service.updateVehicle(shopA, vehicleA, vehicleReq(0, null, "K5", null, null)); // 이제 서버 version은 1

        for (int stale : new int[] {0, 2, -1}) {
            assertThatThrownBy(() -> service.updateVehicle(shopA, vehicleA, vehicleReq(stale, null, "덮어쓰기", null, null)))
                .satisfies(e -> assertErrorCode(e, ErrorCode.CONCURRENT_MODIFICATION));
        }
        assertThat(jdbc.queryForObject("SELECT model FROM vehicle WHERE id = ?", String.class, vehicleA)).isEqualTo("K5");
        assertThat(vehicleVersionInDb(vehicleA)).isEqualTo(1);
    }

    @Test
    @DisplayName("23 X12: 남의 정비소 차량·없는 차량은 404이고 바꾸지 않는다")
    void updateVehicle_notOwned() {
        for (long vehicle : new long[] {vehicleB, 999_999L}) {
            assertThatThrownBy(() -> service.updateVehicle(shopA, vehicle, vehicleReq(0, null, "침범", null, null)))
                .satisfies(e -> assertErrorCode(e, ErrorCode.NOT_FOUND));
        }
        assertThat(jdbc.queryForObject("SELECT model FROM vehicle WHERE id = ?", String.class, vehicleB)).isEqualTo("아반떼");
        assertThat(vehicleVersionInDb(vehicleB)).isZero();
    }

    @Test
    @DisplayName("23: 연식이 현재 연도 + 1을 넘으면 400 INVALID_INPUT(modelYear), version 비교보다 먼저 확인한다")
    void updateVehicle_modelYearUpperBound() {
        int nextYear = LocalDate.now(clock).getYear() + 1;

        assertThatThrownBy(() -> service.updateVehicle(shopA, vehicleA, vehicleReq(99, null, null, nextYear + 1, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting("field").containsExactly("modelYear");
            });
        assertThat(vehicleVersionInDb(vehicleA)).isZero();

        assertThat(service.updateVehicle(shopA, vehicleA, vehicleReq(0, null, null, nextYear, null)).modelYear())
            .isEqualTo(nextYear);
    }

    @Test
    @DisplayName("23·12: 방문 기록이 최근 주행거리를 바꾸면 vehicle.version이 올라 낡은 version의 차량 수정은 409다(의도된 동작)")
    void visitBumpsVehicleVersion() {
        writer.applyVisit(shopA, vehicleA, null, 20_000);
        em.flush();

        assertThat(vehicleVersionInDb(vehicleA)).isEqualTo(1);
        assertThatThrownBy(() -> service.updateVehicle(shopA, vehicleA, vehicleReq(0, null, "K5", null, null)))
            .satisfies(e -> assertErrorCode(e, ErrorCode.CONCURRENT_MODIFICATION));

        // 다시 조회한 version(1)으로는 수정된다
        assertThat(service.getVehicleDetail(shopA, vehicleA).version()).isEqualTo(1);
        assertThat(service.updateVehicle(shopA, vehicleA, vehicleReq(1, null, "K5", null, null)).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("12: 방문 기록이 주행거리를 바꾸지 않으면(같거나 작음) vehicle.version은 그대로다")
    void visitWithoutChangeKeepsVersion() {
        writer.applyVisit(shopA, vehicleA, null, 12_000);
        writer.applyVisit(shopA, vehicleA, null, 1_000);
        em.flush();

        assertThat(vehicleVersionInDb(vehicleA)).isZero();
    }

    private long insertShop(String name, String phone) {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES (?, ?, 'ACTIVE', NOW())", name, phone);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
