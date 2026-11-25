package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import jakarta.persistence.EntityManager;
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
 * 방문 기록 저장 때 차량 반영({@code CustomerWriter.applyVisit}): 실제 vehicle 행이 바뀌는지와 정비소 격리(X5).
 * 앱 전체가 뜨므로 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 테스트 트랜잭션은 끝나면 롤백한다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class CustomerWriterDbTest {

    @Autowired
    private CustomerWriter writer;

    @Autowired
    private CustomerReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    private long shopA;
    private long shopB;
    private long emptyVehicle;
    private long fullVehicle;
    private long vehicleOfB;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소", "02-000-0001");
        shopB = insertShop("B정비소", "02-000-0002");
        long customerA = insertCustomer(shopA, "김민수", "010-0000-0001");
        long customerB = insertCustomer(shopB, "박지성", "010-0000-0002");
        emptyVehicle = insertVehicle(shopA, customerA, "12가0001", null, null);
        fullVehicle = insertVehicle(shopA, customerA, "12가0002", 2018, 80_000);
        vehicleOfB = insertVehicle(shopB, customerB, "34나0003", null, null);
    }

    @Test
    @DisplayName("비어 있는 차량에 연식·주행거리가 저장되고 읽기 계약으로도 보인다")
    void fillsEmptyVehicle() {
        writer.applyVisit(shopA, emptyVehicle, 2019, 55_000);
        em.flush();

        assertThat(row(emptyVehicle)).containsEntry("model_year", 2019).containsEntry("latest_mileage", 55_000);
        em.clear();
        assertThat(reader.getOwnedVehicle(shopA, emptyVehicle).latestMileage()).isEqualTo(55_000);
    }

    @Test
    @DisplayName("더 큰 주행거리만 갱신하고, 작은 값·이미 있는 연식은 그대로 둔다")
    void keepsExistingAndSmaller() {
        writer.applyVisit(shopA, fullVehicle, 2021, 1_000);
        em.flush();
        assertThat(row(fullVehicle)).containsEntry("model_year", 2018).containsEntry("latest_mileage", 80_000);

        writer.applyVisit(shopA, fullVehicle, null, 90_000);
        em.flush();
        assertThat(row(fullVehicle)).containsEntry("latest_mileage", 90_000);
    }

    @Test
    @DisplayName("X5: 남의 정비소 차량·없는 차량은 404이고 B 차량 값은 바뀌지 않는다")
    void notOwnedVehicle() {
        for (long vehicle : new long[] {vehicleOfB, 999_999L}) {
            assertThatThrownBy(() -> writer.applyVisit(shopA, vehicle, 2019, 55_000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        em.flush();

        assertThat(row(vehicleOfB)).containsEntry("model_year", null).containsEntry("latest_mileage", null);
    }

    private Map<String, Object> row(long vehicleId) {
        return jdbc.queryForMap("SELECT model_year, latest_mileage FROM vehicle WHERE id = ?", vehicleId);
    }

    private long insertShop(String name, String phone) {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES (?, ?, 'ACTIVE', NOW())", name, phone);
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
}
