package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest.VehicleInput;
import com.wanted.chagok.domain.customer.dto.VehicleAddResponse;
import com.wanted.chagok.domain.customer.dto.VehicleDetailResponse;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 차량 추가(API 08)·차량 상세(API 09): 저장 정합성(Entity ↔ V1 {@code validate}), 정비소 격리(X17·X2).
 * 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 테스트 트랜잭션은 끝나면 롤백한다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class VehicleServiceDbTest {

    @Autowired
    private CustomerService service;

    @Autowired
    private CustomerReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long shopA;
    private long shopB;
    private long customerA;
    private long customerB;
    private long vehicleB;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소", "02-000-0001");
        shopB = insertShop("B정비소", "02-000-0002");
        customerA = service.register(shopA, request("김민수", "010-0000-0001", "12가0001")).customerId();
        var b = service.register(shopB, request("박지성", "010-0000-0002", "34나0002"));
        customerB = b.customerId();
        vehicleB = b.vehicleId();
    }

    private static CustomerCreateRequest request(String name, String phone, String plate) {
        return new CustomerCreateRequest(name, phone, true, new VehicleInput(plate, "아반떼", 2019, 12_000));
    }

    private int vehicleCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM vehicle", Integer.class);
    }

    @Test
    @DisplayName("08: 기존 고객에 차량이 저장되고(Entity validate·타임스탬프 포함) 값이 그대로 읽힌다, 차량번호 중복도 허용")
    void addVehicle_saves() {
        VehicleAddResponse added = service.addVehicle(shopA, customerA, new VehicleInput("66 카 0011", "K3", null, null));

        assertThat(added.customerId()).isEqualTo(customerA);
        VehicleRef vehicle = reader.getOwnedVehicle(shopA, added.vehicleId());
        assertThat(vehicle.customerId()).isEqualTo(customerA);
        assertThat(vehicle.plateNumber()).isEqualTo("66카0011");
        assertThat(vehicle.model()).isEqualTo("K3");
        assertThat(vehicle.modelYear()).isNull();
        assertThat(vehicle.latestMileage()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE id = ? AND created_at IS NOT NULL AND updated_at IS NOT NULL",
            Integer.class, added.vehicleId())).isEqualTo(1);

        int before = vehicleCount();
        service.addVehicle(shopA, customerA, new VehicleInput("66카0011", "K3", 2020, 1_000));
        assertThat(vehicleCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("08 X17: 남의 정비소 고객·없는 고객에 추가하면 404이고 차량을 저장하지 않는다")
    void addVehicle_notOwnedCustomer() {
        int before = vehicleCount();

        for (long customer : new long[] {customerB, 999_999L}) {
            assertThatThrownBy(() -> service.addVehicle(shopA, customer, new VehicleInput("66카0011", "K3", null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        assertThat(vehicleCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("08: 연식이 현재 연도 + 1을 넘으면 400 INVALID_INPUT(modelYear)이고 저장하지 않는다")
    void addVehicle_modelYearUpperBound() {
        int nextYear = LocalDate.now(clock).getYear() + 1;
        int before = vehicleCount();

        assertThatThrownBy(() -> service.addVehicle(shopA, customerA, new VehicleInput("66카0011", "K3", nextYear + 1, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting("field").containsExactly("modelYear");
            });
        assertThat(vehicleCount()).isEqualTo(before);

        service.addVehicle(shopA, customerA, new VehicleInput("66카0011", "K3", nextYear, null));
        assertThat(vehicleCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("09: 차량과 고객(연락처 전체)이 읽히고, 비어 있는 연식·주행거리는 null")
    void vehicleDetail() {
        long vehicle = service.addVehicle(shopA, customerA, new VehicleInput("66카0011", "K3", null, null)).vehicleId();

        VehicleDetailResponse detail = service.getVehicleDetail(shopA, vehicle);

        assertThat(detail.vehicleId()).isEqualTo(vehicle);
        assertThat(detail.plateNumber()).isEqualTo("66카0011");
        assertThat(detail.model()).isEqualTo("K3");
        assertThat(detail.modelYear()).isNull();
        assertThat(detail.latestMileage()).isNull();
        assertThat(detail.customer().customerId()).isEqualTo(customerA);
        assertThat(detail.customer().name()).isEqualTo("김민수");
        assertThat(detail.customer().phone()).isEqualTo("010-0000-0001");
        assertThat(detail.customer().consent()).isTrue();
    }

    @Test
    @DisplayName("09 X2: 남의 정비소 차량·없는 차량은 404 NOT_FOUND")
    void vehicleDetail_notOwned() {
        for (long vehicle : new long[] {vehicleB, 999_999L}) {
            assertThatThrownBy(() -> service.getVehicleDetail(shopA, vehicle))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        assertThat(service.getVehicleDetail(shopB, vehicleB).customer().customerId()).isEqualTo(customerB);
    }

    private long insertShop(String name, String phone) {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES (?, ?, 'ACTIVE', NOW())", name, phone);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
