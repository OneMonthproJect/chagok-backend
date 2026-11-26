package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담당 정비사 규칙을 실제 표(V1·V2)·실제 account 구현으로 확인한다 (08 16·19번, R15~R20, X13): 담당 검증(다른 정비소 직원은 400),
 * 같은 담당·같은 시작 시각의 예약·진행중 예약만 409 {@code MECHANIC_SLOT_CONFLICT}(취소·완료·노쇼는 통과), 같은 시각 다른
 * 담당 허용, 변경(19)의 충돌과 자기 자신 제외, 담당과 등록자가 다르게 저장되는 것. 앱 전체가 뜨므로 MySQL이 필요하다
 * ({@code ./gradlew dbTest}). 정비소·계정·고객·차량은 이 테스트가 넣고 롤백한다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReservationAssigneeDbTest {

    private static final LocalDate TODAY = LocalDate.now(ClockConfig.SEOUL);
    private static final LocalDateTime SLOT = TODAY.plusDays(3).atTime(10, 0);

    @Autowired
    private ReservationService service;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    @Value("${chagok.jwt.secret}")
    private String secret;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private long shopA;
    private long shopB;
    private long ownerA;
    private long mechanicA;
    private long staffB;
    private long vehicle1;
    private long vehicle2;
    private long vehicle3;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소" + suffix);
        shopB = insertShop("B정비소" + suffix);
        ownerA = insertStaff(shopA, "t_oa_" + suffix, "박사장", "OWNER");
        mechanicA = insertStaff(shopA, "t_ma_" + suffix, "김정비", "MECHANIC");
        staffB = insertStaff(shopB, "t_sb_" + suffix, "타정비소사람", "MECHANIC");
        long customer = insertCustomer(shopA, "김민수", "010-0000-0001");
        vehicle1 = insertVehicle(shopA, customer, "12가0001");
        vehicle2 = insertVehicle(shopA, customer, "12가0002");
        vehicle3 = insertVehicle(shopA, customer, "12가0003");
    }

    private ReservationCreateResponse create(long vehicleId, LocalDateTime at, long assigneeId) {
        return service.create(shopA, ownerA, new ReservationCreateRequest(vehicleId, at, null, assigneeId));
    }

    private static void assertConflictWith(Runnable action, long reservationId) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.MECHANIC_SLOT_CONFLICT);
            assertThat(e.details()).isEqualTo(Map.of("reservationId", reservationId));
        });
    }

    // ------------------------------------------------------------------ 담당 검증 (R20·X13)

    @Test
    @DisplayName("담당은 요청 값으로 저장되고 등록자와 따로다: 사장이 정비사를 담당으로 등록하면 assignee=정비사, created_by=사장")
    void assigneeAndCreatorStoredSeparately() {
        ReservationCreateResponse created = create(vehicle1, SLOT, mechanicA);
        em.flush();
        em.clear();

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT assignee_staff_id, created_by_staff_id FROM reservation WHERE id = ?", created.reservationId());
        assertThat(((Number) row.get("assignee_staff_id")).longValue()).isEqualTo(mechanicA);
        assertThat(((Number) row.get("created_by_staff_id")).longValue()).isEqualTo(ownerA);
    }

    @Test
    @DisplayName("R20·X13 다른 정비소 정비사·없는 id를 담당으로 등록하면 400 INVALID_INPUT(assigneeId)이고 저장되지 않는다")
    void assigneeOfOtherShopIsRejected() {
        for (long bad : new long[] {staffB, -1L}) {
            assertThatThrownBy(() -> create(vehicle1, SLOT, bad)).isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting("field").containsExactly("assigneeId");
            });
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE shop_id = ?", Long.class, shopA)).isZero();
    }

    // ------------------------------------------------------------------ 담당 중복 (R15~R17)

    @Test
    @DisplayName("R15 같은 담당·같은 시작 시각에 예약이 있으면 409 MECHANIC_SLOT_CONFLICT + 겹친 예약 번호")
    void sameAssigneeSameTimeConflicts() {
        long first = create(vehicle1, SLOT, mechanicA).reservationId();
        em.flush();
        em.clear();

        assertConflictWith(() -> create(vehicle2, SLOT, mechanicA), first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE shop_id = ?", Long.class, shopA)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R16 같은 시각이어도 담당이 다르면 등록되고, 같은 담당이라도 시각이 다르면(30분 뒤) 등록된다")
    void otherAssigneeOrOtherTimeIsAllowed() {
        create(vehicle1, SLOT, mechanicA);
        em.flush();
        em.clear();

        assertThat(create(vehicle2, SLOT, ownerA).reservationId()).isPositive();
        assertThat(create(vehicle3, SLOT.plusMinutes(30), mechanicA).reservationId()).isPositive();
    }

    @Test
    @DisplayName("R17 같은 담당·같은 시각이어도 기존 예약이 취소·완료·노쇼면 등록된다, 진행중이면 막는다")
    void onlyReservedAndInProgressBlock() {
        for (String status : new String[] {"CANCELED", "COMPLETED", "NO_SHOW"}) {
            long old = create(vehicle1, SLOT, mechanicA).reservationId();
            jdbc.update("UPDATE reservation SET status = ? WHERE id = ?", status, old);
            em.clear();

            long next = create(vehicle2, SLOT, mechanicA).reservationId();
            assertThat(next).as(status + " 뒤 등록").isPositive();
            jdbc.update("UPDATE reservation SET status = 'CANCELED' WHERE id = ?", next); // 다음 반복을 위해 비움
            em.clear();
        }

        long inProgress = create(vehicle1, SLOT, mechanicA).reservationId();
        jdbc.update("UPDATE reservation SET status = 'IN_PROGRESS' WHERE id = ?", inProgress);
        em.clear();
        assertConflictWith(() -> create(vehicle2, SLOT, mechanicA), inProgress);
    }

    // ------------------------------------------------------------------ 변경 (R18)

    @Test
    @DisplayName("R18 변경(19)으로 같은 담당·같은 시각이 되면 409 MECHANIC_SLOT_CONFLICT, 예약·version은 그대로")
    void updateIntoConflict() {
        long blocker = create(vehicle1, SLOT, mechanicA).reservationId();
        long moving = create(vehicle2, SLOT.plusDays(1), mechanicA).reservationId();
        em.flush();
        em.clear();

        assertConflictWith(() -> service.update(ReservationTestStaff.owner(shopA), moving, new ReservationUpdateRequest(0, SLOT, null, null, null)), blocker);
        em.clear();
        Map<String, Object> row = jdbc.queryForMap("SELECT version FROM reservation WHERE id = ?", moving);
        assertThat(((Number) row.get("version")).intValue()).isZero();
    }

    @Test
    @DisplayName("변경은 자기 자신과 충돌하지 않고(같은 시각 다시 보냄), 다른 시각으로 옮기면 version이 오른다")
    void updateExcludesItselfAndMoves() {
        long id = create(vehicle1, SLOT, mechanicA).reservationId();
        em.flush();
        em.clear();

        ReservationResponse same = service.update(ReservationTestStaff.owner(shopA), id, new ReservationUpdateRequest(0, SLOT, null, null, null));
        em.flush();
        em.clear();
        ReservationResponse moved = service.update(ReservationTestStaff.owner(shopA), id, new ReservationUpdateRequest(same.version(), SLOT.plusMinutes(30), null, null, null));

        assertThat(moved.version()).isGreaterThan(same.version());
        assertThat(moved.reservedAt()).isEqualTo(SLOT.plusMinutes(30));
    }

    // ------------------------------------------------------------------ HTTP (보안 필터·본문 검증)

    private String token(long staffId, long shopId, String role) {
        return "Bearer " + TestJwts.sign(secret, TestJwts.staff(staffId, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    @Test
    @DisplayName("R19 POST /api/reservations에 assigneeId가 없으면 400 INVALID_INPUT(assigneeId), 있고 정상이면 200")
    void httpRequiresAssigneeId() throws Exception {
        String at = SLOT + ":00"; // LocalDateTime.toString은 초가 0이면 "HH:mm"만 내므로 08 형식(초 포함)으로 맞춘다
        mvc.perform(post("/api/reservations").header("Authorization", token(ownerA, shopA, "OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleId\":" + vehicle1 + ",\"reservedAt\":\"" + at + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("assigneeId"));
        mvc.perform(post("/api/reservations").header("Authorization", token(ownerA, shopA, "OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleId\":" + vehicle1 + ",\"reservedAt\":\"" + at + "\",\"assigneeId\":" + mechanicA + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reservationId").isNumber());
    }

    @Test
    @DisplayName("HTTP: 같은 담당·시각 두 번째 등록은 409 MECHANIC_SLOT_CONFLICT + details.reservationId, 다른 정비소 직원은 400 (R15·X13)")
    void httpConflictAndForeignAssignee() throws Exception {
        String at = SLOT + ":00"; // LocalDateTime.toString은 초가 0이면 "HH:mm"만 내므로 08 형식(초 포함)으로 맞춘다
        long first = create(vehicle1, SLOT, mechanicA).reservationId();
        em.flush();
        em.clear();

        mvc.perform(post("/api/reservations").header("Authorization", token(ownerA, shopA, "OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleId\":" + vehicle2 + ",\"reservedAt\":\"" + at + "\",\"assigneeId\":" + mechanicA + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MECHANIC_SLOT_CONFLICT"))
            .andExpect(jsonPath("$.error.details.reservationId").value(first));
        mvc.perform(post("/api/reservations").header("Authorization", token(ownerA, shopA, "OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleId\":" + vehicle3 + ",\"reservedAt\":\"" + at + "\",\"assigneeId\":" + staffB + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("assigneeId"));
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

    private long lastInsertId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
