package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.customer.api.CustomerWriter;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code CustomerWriter.applyVisit}의 트랜잭션 동작: (1) 단독 호출도 저장된다(클래스 기본 {@code readOnly=true}를 덮어쓴 것),
 * (2) 호출한 쪽 트랜잭션에 참여해 바깥이 실패하면 함께 취소된다(방문 기록 저장 실패 시 차량도 그대로, 이슈 #42 저장 정합성).
 *
 * <p>다른 DB 테스트와 달리 클래스에 {@code @Transactional}을 붙이지 않는다(붙이면 위 두 가지를 구분할 수 없다).
 * 대신 이 테스트가 넣은 행을 {@code @AfterEach}에서 직접 지운다. 빈 MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test).
 */
@Tag("db")
@SpringBootTest
class CustomerWriterTransactionDbTest {

    @Autowired
    private CustomerWriter writer;

    @Autowired
    private com.wanted.chagok.domain.customer.api.CustomerReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    private long shop;
    private long vehicle;

    @BeforeEach
    void data() {
        // INSERT와 LAST_INSERT_ID()는 같은 연결이어야 하므로 한 트랜잭션(한 연결)으로 묶는다.
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES ('트랜잭션시험정비소', '02-900-0001', 'ACTIVE', NOW())");
            shop = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) VALUES (?, '시험고객', '010-9000-0001', FALSE, NOW(), NOW())", shop);
            long customer = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, model, created_at, updated_at) VALUES (?, ?, '90가0001', '아반떼', NOW(), NOW())",
                shop, customer);
            vehicle = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM vehicle WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM customer WHERE shop_id = ?", shop);
        jdbc.update("DELETE FROM shop WHERE id = ?", shop);
    }

    @Test
    @DisplayName("단독 호출: 바깥 트랜잭션이 없어도 차량 값이 저장된다")
    void standaloneCallCommits() {
        writer.applyVisit(shop, vehicle, 2019, 55_000);

        assertThat(row()).containsEntry("model_year", 2019).containsEntry("latest_mileage", 55_000);
    }

    @Test
    @DisplayName("참여: 바깥 트랜잭션이 실패하면 차량 값도 함께 취소된다")
    void rollsBackWithOuterTransaction() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            writer.applyVisit(shop, vehicle, 2019, 55_000);
            throw new IllegalStateException("기록 저장 실패를 흉내");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(row()).containsEntry("model_year", null).containsEntry("latest_mileage", null);
    }

    @Test
    @DisplayName("참여: 바깥 트랜잭션이 성공하면 차량 값이 함께 커밋된다")
    void commitsWithOuterTransaction() {
        tx.executeWithoutResult(status -> writer.applyVisit(shop, vehicle, 2019, 55_000));

        assertThat(row()).containsEntry("model_year", 2019).containsEntry("latest_mileage", 55_000);
    }

    @Test
    @DisplayName("동시 저장: 먼저 읽어 둔 차량 값이 낡았어도(다른 저장이 더 큰 주행거리를 커밋) 최근 주행거리가 낮아지지 않는다")
    void staleReadDoesNotLowerMileage() {
        tx.executeWithoutResult(status -> {
            reader.getOwnedVehicle(shop, vehicle); // saveVisit처럼 차량을 먼저 읽어 영속성 컨텍스트에 올린다 (주행거리 없음)
            // 다른 방문 저장이 그사이 60,000을 커밋한 것을 흉내: 별도 트랜잭션(REQUIRES_NEW)으로 커밋한다.
            new TransactionTemplate(tx.getTransactionManager(), requiresNew()).executeWithoutResult(
                other -> jdbc.update("UPDATE vehicle SET latest_mileage = 60000 WHERE id = ?", vehicle));
            writer.applyVisit(shop, vehicle, null, 50_000);
        });

        assertThat(row()).containsEntry("latest_mileage", 60_000);
    }

    private static org.springframework.transaction.support.DefaultTransactionDefinition requiresNew() {
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return definition;
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT model_year, latest_mileage FROM vehicle WHERE id = ?", vehicle);
    }
}
