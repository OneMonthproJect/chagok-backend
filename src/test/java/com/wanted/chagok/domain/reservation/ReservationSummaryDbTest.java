package com.wanted.chagok.domain.reservation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.global.auth.TestJwts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 42 월 요약과 API 15 {@code assigneeId} 쿼리를 실제 보안 필터·표(V1·V2)로 확인한다 (08 7.1 42, 08 15번, X7, X16).
 * 상태 {@code IN_PROGRESS}·{@code NO_SHOW}는 enum에 없어도 V2 CHECK가 허용하므로 행을 직접 넣어 문자열 집계를 확인한다.
 * 앱 전체가 뜨므로 MySQL이 필요하다({@code ./gradlew dbTest}). 정비소·계정·고객·차량·예약은 이 테스트가 넣고 롤백한다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReservationSummaryDbTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationRepository reservations;

    @PersistenceContext
    private EntityManager em;

    @Value("${chagok.jwt.secret}")
    private String secret;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final Instant later = Instant.now().plusSeconds(3_600);

    private long shopA;
    private long shopB;
    private long ownerA;
    private long mechanicA;
    private long staffB;
    private long vehicleA;
    private long vehicleB;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소" + suffix);
        shopB = insertShop("B정비소" + suffix);
        ownerA = insertStaff(shopA, "t_oa_" + suffix, "박사장", "OWNER");
        mechanicA = insertStaff(shopA, "t_ma_" + suffix, "김정비", "MECHANIC");
        staffB = insertStaff(shopB, "t_sb_" + suffix, "타정비소사람", "MECHANIC");
        long customerA = insertCustomer(shopA, "김민수", "010-0000-0001");
        long customerB = insertCustomer(shopB, "박지영", "010-0000-0009");
        vehicleA = insertVehicle(shopA, customerA, "12가0001");
        vehicleB = insertVehicle(shopB, customerB, "99나0009");
    }

    private String token(long staffId, long shopId, String role) {
        return "Bearer " + TestJwts.sign(secret, TestJwts.staff(staffId, shopId, role, later));
    }

    private void summarizeNovember(String auth, int items) throws Exception {
        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-01").param("to", "2026-11-30")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(items));
    }

    // ------------------------------------------------------------------ API 42

    @Test
    @DisplayName("42 날짜별 건수: 상태별 건수, total은 취소만 제외(진행중·노쇼 포함), 기간 경계(1일·30일 포함, 전후 제외), 예약 없는 날짜는 없음")
    void countsPerDay() throws Exception {
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 10, 31, 23, 30), "RESERVED", ownerA); // 기간 밖(전)
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 23, 10, 0), "RESERVED", ownerA);
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 23, 11, 0), "RESERVED", mechanicA);
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 23, 12, 0), "COMPLETED", ownerA);
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 23, 13, 0), "CANCELED", ownerA);
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 24, 9, 30), "IN_PROGRESS", ownerA);
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 30, 23, 30), "NO_SHOW", ownerA); // 마지막 날 포함
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 12, 1, 0, 0), "RESERVED", ownerA); // 기간 밖(후)

        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-01").param("to", "2026-11-30")
                .header("Authorization", token(ownerA, shopA, "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.from").value("2026-11-01"))
            .andExpect(jsonPath("$.data.to").value("2026-11-30"))
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].date").value("2026-11-23"))
            .andExpect(jsonPath("$.data.items[0].total").value(3))
            .andExpect(jsonPath("$.data.items[0].reserved").value(2))
            .andExpect(jsonPath("$.data.items[0].inProgress").value(0))
            .andExpect(jsonPath("$.data.items[0].completed").value(1))
            .andExpect(jsonPath("$.data.items[0].canceled").value(1))
            .andExpect(jsonPath("$.data.items[0].noShow").value(0))
            .andExpect(jsonPath("$.data.items[1].date").value("2026-11-24"))
            .andExpect(jsonPath("$.data.items[1].total").value(1))
            .andExpect(jsonPath("$.data.items[1].inProgress").value(1))
            .andExpect(jsonPath("$.data.items[2].date").value("2026-11-30"))
            .andExpect(jsonPath("$.data.items[2].total").value(1))
            .andExpect(jsonPath("$.data.items[2].noShow").value(1));
    }

    @Test
    @DisplayName("42 격리(X7): A 토큰에 B 정비소 예약이 섞이지 않고, B 토큰은 B 것만 본다. 정비사도 같다")
    void shopIsolation() throws Exception {
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 23, 10, 0), "RESERVED", ownerA);
        insertReservation(shopB, vehicleB, LocalDateTime.of(2026, 11, 23, 10, 0), "RESERVED", staffB);
        insertReservation(shopB, vehicleB, LocalDateTime.of(2026, 11, 25, 10, 0), "COMPLETED", staffB);

        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-01").param("to", "2026-11-30")
                .param("shopId", String.valueOf(shopB)).header("Authorization", token(mechanicA, shopA, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].date").value("2026-11-23"))
            .andExpect(jsonPath("$.data.items[0].total").value(1));
        summarizeNovember(token(staffB, shopB, "MECHANIC"), 2);
    }

    @Test
    @DisplayName("42 예약이 없는 정비소·기간은 items가 [] 이다")
    void emptyPeriod() throws Exception {
        summarizeNovember(token(ownerA, shopA, "OWNER"), 0);
    }

    @Test
    @DisplayName("42 토큰 없음 → 401 AUTH_REQUIRED, 서비스 관리자 토큰 → 403 FORBIDDEN (X16)")
    void authAndRole() throws Exception {
        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-01").param("to", "2026-11-30"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-01").param("to", "2026-11-30")
                .header("Authorization", "Bearer " + TestJwts.sign(secret, TestJwts.admin(1, later))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("42 기간 규칙: 42일은 통과, 43일·시작>끝·누락은 400 INVALID_INPUT")
    void periodRule() throws Exception {
        String auth = token(ownerA, shopA, "OWNER");
        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-01").param("to", "2026-12-12").header("Authorization", auth))
            .andExpect(status().isOk()); // 42일
        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-01").param("to", "2026-12-13").header("Authorization", auth))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("to")); // 43일
        mvc.perform(get("/api/reservations/summary").param("from", "2026-11-05").param("to", "2026-11-04").header("Authorization", auth))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("from"));
        mvc.perform(get("/api/reservations/summary").param("to", "2026-11-04").header("Authorization", auth))
            .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ API 15 assigneeId

    @Test
    @DisplayName("15 assigneeId: 그 담당의 예약만, 없는 담당·다른 정비소 담당 id는 오류가 아니라 빈 목록, 주지 않으면 정비소 전체")
    void listFilterByAssignee() throws Exception {
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 23, 10, 0), "RESERVED", ownerA);
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 23, 11, 0), "RESERVED", mechanicA);
        insertReservation(shopA, vehicleA, LocalDateTime.of(2026, 11, 23, 12, 0), "COMPLETED", mechanicA);
        insertReservation(shopB, vehicleB, LocalDateTime.of(2026, 11, 23, 10, 0), "RESERVED", staffB);
        String auth = token(ownerA, shopA, "OWNER");

        mvc.perform(get("/api/reservations").param("from", "2026-11-23").param("to", "2026-11-23")
                .param("assigneeId", String.valueOf(mechanicA)).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].reservedAt").value("2026-11-23T11:00:00"))
            .andExpect(jsonPath("$.data.items[1].reservedAt").value("2026-11-23T12:00:00"));
        mvc.perform(get("/api/reservations").param("from", "2026-11-23").param("to", "2026-11-23")
                .param("assigneeId", String.valueOf(ownerA)).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1));
        // 다른 정비소 담당 id와 없는 id: 화면 필터일 뿐이라 오류가 아니고, 다른 정비소 예약은 나오지 않는다
        mvc.perform(get("/api/reservations").param("from", "2026-11-23").param("to", "2026-11-23")
                .param("assigneeId", String.valueOf(staffB)).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/reservations").param("from", "2026-11-23").param("to", "2026-11-23")
                .param("assigneeId", "-1").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());
        // assigneeId 없음 = 기존 동작: A 정비소 전체, B 정비소는 없다
        mvc.perform(get("/api/reservations").param("from", "2026-11-23").param("to", "2026-11-23").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(3));
    }

    // ------------------------------------------------------------------ 데이터

    private long insertShop(String name) {
        jdbc.update("INSERT INTO shop (name, phone, created_at) VALUES (?, ?, ?)", name, "02-000-0000", LocalDateTime.now());
        return lastInsertId();
    }

    private long insertStaff(long shopId, String loginId, String name, String role) {
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            shopId, loginId, "x", name, role, LocalDateTime.now());
        return lastInsertId();
    }

    private long insertCustomer(long shopId, String name, String phone) {
        jdbc.update("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            shopId, name, phone, true, LocalDateTime.now(), LocalDateTime.now());
        return lastInsertId();
    }

    private long insertVehicle(long shopId, long customerId, String plate) {
        jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            shopId, customerId, plate, LocalDateTime.now(), LocalDateTime.now());
        return lastInsertId();
    }

    /**
     * 시각이 들어가는 예약 행은 앱과 같은 방식(Hibernate)으로 저장한다. JdbcTemplate으로 {@code LocalDateTime}을 넣으면 JVM
     * 시간대(CI는 UTC)로 해석되어 앱이 읽는 값과 어긋난다. 상태만 바꾸는 UPDATE는 시각과 무관해 JdbcTemplate을 쓴다
     * ({@code IN_PROGRESS}·{@code NO_SHOW}는 enum에 없어도 V2 CHECK가 허용한다).
     */
    private long insertReservation(long shopId, long vehicleId, LocalDateTime at, String status, long staffId) {
        Reservation saved = reservations.saveAndFlush(Reservation.register(shopId, vehicleId, at, false, null, staffId, staffId));
        if (!"RESERVED".equals(status)) {
            jdbc.update("UPDATE reservation SET status = ? WHERE id = ?", status, saved.getId());
            em.clear();
        }
        return saved.getId();
    }

    private long lastInsertId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
