package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerRef;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 상세용 읽기 계약({@code CustomerReader.getOwnedCustomer}): 실제 쿼리로 고객·차량이 읽히는지(차량 id 순서, 비어 있는 값)와
 * 정비소 격리(X1). 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 테스트 트랜잭션은 끝나면 롤백한다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class CustomerReaderDbTest {

    @Autowired
    private CustomerReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    private long shopA;
    private long shopB;
    private long customerA;
    private long customerB;
    private long customerWithoutVehicle;
    private long vehicle1;
    private long vehicle2;
    private long vehicleOfB;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소", "02-000-0001");
        shopB = insertShop("B정비소", "02-000-0002");
        customerA = insertCustomer(shopA, "오세훈", "010-0000-0010", true);
        customerWithoutVehicle = insertCustomer(shopA, "차없음", "010-0000-0011", false);
        customerB = insertCustomer(shopB, "박지성", "010-0000-0002", false);
        vehicle1 = insertVehicle(shopA, customerA, "66카0011", "K3", null, null);
        vehicle2 = insertVehicle(shopA, customerA, "55차0010", "쏘나타", 2018, 97_000);
        vehicleOfB = insertVehicle(shopB, customerB, "34나0003", "아반떼", 2019, 10_000);
    }

    @Test
    @DisplayName("고객과 차량이 번호판이 아니라 id 순서로 읽히고(Entity validate 포함) 연락처는 전체 번호, 비어 있는 연식·주행거리는 null")
    void readsCustomerWithVehicles() {
        CustomerRef ref = reader.getOwnedCustomer(shopA, customerA);

        assertThat(ref.customerId()).isEqualTo(customerA);
        assertThat(ref.name()).isEqualTo("오세훈");
        assertThat(ref.phone()).isEqualTo("010-0000-0010");
        assertThat(ref.consent()).isTrue();
        assertThat(ref.vehicles()).containsExactly(
            new VehicleRef(vehicle1, customerA, "66카0011", "K3", null, null),
            new VehicleRef(vehicle2, customerA, "55차0010", "쏘나타", 2018, 97_000));
    }

    @Test
    @DisplayName("차량이 없는 고객은 빈 목록, 다른 정비소 차량은 섞이지 않는다")
    void noVehiclesAndNoMixing() {
        assertThat(reader.getOwnedCustomer(shopA, customerWithoutVehicle).vehicles()).isEmpty();
        assertThat(reader.getOwnedCustomer(shopB, customerB).vehicles()).extracting(VehicleRef::vehicleId)
            .containsExactly(vehicleOfB);
    }

    @Test
    @DisplayName("X1: A 정비소로 B 고객·없는 고객을 읽으면 404 NOT_FOUND")
    void notOwnedCustomer() {
        for (long customer : new long[] {customerB, 999_999L}) {
            assertThatThrownBy(() -> reader.getOwnedCustomer(shopA, customer))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    private long insertShop(String name, String phone) {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES (?, ?, 'ACTIVE', NOW())", name, phone);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertCustomer(long shopId, String name, String phone, boolean consent) {
        jdbc.update("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
            shopId, name, phone, consent);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertVehicle(long shopId, long customerId, String plate, String model, Integer year, Integer mileage) {
        jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
            shopId, customerId, plate, model, year, mileage);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
