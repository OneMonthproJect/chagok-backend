package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 서로 다른 트랜잭션의 <b>동시 요청</b>: 같은 {@code version}으로 두 요청이 부딪힐 때 하나만 성공하고 나머지는 409인지(04 R8·R10 둘째 경우,
 * 08 18·19번 "다른 직원이 먼저 바꿈"), 진 쪽이 아무것도 바꾸지 않는지(목적 행 삭제도 되돌려짐)를 실제 MySQL에서 본다. 보안 필터·JWT·Controller·Service·
 * 커밋 시점의 낙관적 락 충돌과 전역 처리기의 409 매핑까지 연결된다. 앱 전체가 뜨므로 MySQL이 필요하다(CI db-test).
 *
 * <p>다른 DB 테스트와 달리 <b>트랜잭션 롤백을 쓰지 않는다</b>(스레드마다 트랜잭션이 따로라 커밋된 데이터가 필요). 그래서 이 테스트가 만든 행은
 * 매번 {@code @AfterEach}에서 직접 지운다. 정비소 이름이 매번 달라 다른 테스트와 겹치지 않는다.
 *
 * <p>LIMIT: 두 스레드를 동시에 출발시키지만 어느 쪽이 먼저 읽는지는 보장하지 못한다. 그래서 진 쪽이 낙관적 락 충돌로 지든(둘 다 읽은 뒤) 일반
 * {@code version} 불일치로 지든(먼저 커밋된 뒤 읽음) <b>결과가 같은 규칙</b>(성공 1·409 1·최종 상태 한 쪽 값)만 단정하고, 라운드를 여러 번 돌려
 * 실제 경쟁이 생길 기회를 늘린다. 어느 경로로 졌는지는 단정하지 않는다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
class ReservationConcurrencyDbTest {

    private static final int ROUNDS = 8;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationRepository reservations;

    @Value("${chagok.jwt.secret}")
    private String secret;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final LocalDate today = LocalDate.now(ClockConfig.SEOUL);

    private long shopId;
    private long staffId;
    private long customerId;
    private long itemOld;
    private long itemX;
    private long itemY;
    private int vehicleSeq;

    @BeforeEach
    void data() {
        shopId = insertAndGetId("INSERT INTO shop (name, phone, created_at) VALUES (?, ?, ?)", "동시성테스트" + suffix, "02-000-0000", LocalDateTime.now());
        staffId = insertAndGetId("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            shopId, "t_cc_" + suffix, "x", "직원", "OWNER", LocalDateTime.now());
        customerId = insertAndGetId("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            shopId, "동시성고객", "010-0000-0000", true, LocalDateTime.now(), LocalDateTime.now());
        itemOld = insertItem("OLD_ITEM", 1);
        itemX = insertItem("ITEM_X", 2);
        itemY = insertItem("ITEM_Y", 3);
    }

    @AfterEach
    void cleanUp() {
        // FK 순서: 목적 행 → 예약 → 차량 → 고객 → 항목 → 직원 → 정비소. 테스트가 실패해도 남기지 않는다
        jdbc.update("DELETE FROM reservation_item WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM reservation WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM vehicle WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM customer WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM service_item WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM staff WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM shop WHERE id = ?", shopId);
    }

    @Test
    @DisplayName("R8 같은 version 0으로 두 변경이 동시에: 정확히 하나만 200(version 1), 다른 하나는 409 CONCURRENT_MODIFICATION, 최종 일시·목적 행은 이긴 쪽 값(진 쪽의 삭제는 되돌려짐)")
    void concurrentUpdateOnlyOneWins() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long id = newReservation(true);
            LocalDateTime atA = today.plusDays(10).atTime(9, 0);
            LocalDateTime atB = today.plusDays(11).atTime(9, 0);

            List<Outcome> results = race(
                () -> send(patch("/api/reservations/" + id),
                    "{\"version\":0,\"reservedAt\":\"" + atA + "\",\"purpose\":{\"itemIds\":[" + itemX + "]}}"),
                () -> send(patch("/api/reservations/" + id),
                    "{\"version\":0,\"reservedAt\":\"" + atB + "\",\"purpose\":{\"itemIds\":[" + itemY + "]}}"));

            assertThat(results).extracting(Outcome::status).as("라운드 %d", round).containsExactlyInAnyOrder(200, 409);
            Outcome loser = results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
            assertThat(loser.code()).isEqualTo("CONCURRENT_MODIFICATION");

            boolean aWon = results.get(0).status() == 200;
            Reservation saved = reservations.findById(id).orElseThrow();
            assertThat(saved.getVersion()).as("라운드 %d: version은 정확히 1", round).isEqualTo(1);
            assertThat(saved.getReservedAt()).isEqualTo(aWon ? atA : atB);
            assertThat(itemIds(id)).as("라운드 %d: 목적 행은 이긴 쪽 값 하나", round).containsExactly(aWon ? itemX : itemY);
        }
    }

    @Test
    @DisplayName("R10 둘째 경우 같은 version 0으로 방문 완료가 동시에: 하나만 200(COMPLETED, version 1), 다른 하나는 409, 최종 상태는 한 번만 바뀐다")
    void concurrentCompleteOnlyOneWins() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long id = newReservation(false);

            List<Outcome> results = race(
                () -> send(post("/api/reservations/" + id + "/complete"), "{\"version\":0}"),
                () -> send(post("/api/reservations/" + id + "/complete"), "{\"version\":0}"));

            assertThat(results).extracting(Outcome::status).as("라운드 %d", round).containsExactlyInAnyOrder(200, 409);
            Outcome loser = results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
            // 둘 다 읽은 뒤 부딪히면 CONCURRENT_MODIFICATION, 먼저 끝난 뒤 읽으면 INVALID_STATUS_TRANSITION (08 18번 서버 처리 순서)
            assertThat(loser.code()).isIn("CONCURRENT_MODIFICATION", "INVALID_STATUS_TRANSITION");

            Reservation saved = reservations.findById(id).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
            assertThat(saved.getVersion()).as("라운드 %d: version은 정확히 1", round).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Outcome(int status, String code) {
    }

    private List<Outcome> race(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Outcome send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body)
            throws Exception {
        String token = "Bearer " + TestJwts.sign(secret, TestJwts.staff(staffId, shopId, "OWNER", Instant.now().plusSeconds(3600)));
        var response = mvc.perform(request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();
        String json = response.getContentAsString(StandardCharsets.UTF_8);
        String code = response.getStatus() == 200 ? null : JsonPath.<String>read(json, "$.error.code");
        return new Outcome(response.getStatus(), code);
    }

    /** 라운드마다 새 차량·새 예약(커밋됨). 시각은 앱과 같은 방식(Hibernate)으로 저장한다. */
    private long newReservation(boolean withOldItem) {
        long vehicleId = insertAndGetId("INSERT INTO vehicle (shop_id, customer_id, plate_number, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            shopId, customerId, "CC" + suffix + (++vehicleSeq), LocalDateTime.now(), LocalDateTime.now());
        long id = reservations.saveAndFlush(
            Reservation.register(shopId, vehicleId, today.plusDays(8).atTime(10, 0), false, null, staffId)).getId();
        if (withOldItem) {
            jdbc.update("INSERT INTO reservation_item (shop_id, reservation_id, service_item_id, created_at) VALUES (?, ?, ?, ?)",
                shopId, id, itemOld, LocalDateTime.now());
        }
        return id;
    }

    private List<Long> itemIds(long reservationId) {
        return jdbc.queryForList("SELECT service_item_id FROM reservation_item WHERE reservation_id = ? ORDER BY service_item_id",
            Long.class, reservationId);
    }

    private long insertItem(String code, int sortOrder) {
        return insertAndGetId("INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", shopId, code, code, 6, 10_000, true, sortOrder, LocalDateTime.now());
    }

    /** INSERT와 같은 호출에서 생성 키를 받는다. {@code LAST_INSERT_ID()}는 연결마다 따로라 별도 호출로 읽으면 다른 연결을 만날 수 있다. */
    private long insertAndGetId(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}
