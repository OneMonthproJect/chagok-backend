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
    @DisplayName("동시 저장: 방문 저장이 차량 행을 잠그면 다른 저장은 끝날 때까지 기다리고, 그 뒤에도 주행거리가 낮아지지 않는다")
    void lockedVehicleMakesOtherSaveWaitAndKeepsHigherMileage() throws Exception {
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tx.executeWithoutResult(status -> {
                writer.getOwnedVehicleForVisit(shop, vehicle); // saveVisit의 첫 차량 조회: 행 잠금
                locked.countDown();
                await(release);
                writer.applyVisit(shop, vehicle, null, 60_000);
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> tx.executeWithoutResult(status -> {
                writer.getOwnedVehicleForVisit(shop, vehicle);
                writer.applyVisit(shop, vehicle, null, 50_000);
            }));

            assertThatThrownBy(() -> second.get(1, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(row()).containsEntry("latest_mileage", 60_000);
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT model_year, latest_mileage FROM vehicle WHERE id = ?", vehicle);
    }
}
