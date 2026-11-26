package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
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
 * 담당 재배정(API 19, R24·D-53)을 실제 표(V1·V2)·실제 account 구현으로 확인한다: 이력이 {@code reservation_revision}에 저장되는 것,
 * 역할이 토큰에서 와서 당일 정비사는 409·당일 사장은 사유 필수인 것, 지난 날짜 불가, 실패하면 이력도 남지 않는 것, 다른 정비소
 * 정비사·예약 격리(X13·X14). 앱 전체가 뜨므로 MySQL이 필요하다({@code ./gradlew dbTest}). 정비소·계정·고객·차량은 이 테스트가 넣고
 * 롤백한다. 날짜는 실제 오늘({@code Asia/Seoul}) 기준이다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReservationReassignDbTest {

    private static final LocalDate TODAY = LocalDate.now(ClockConfig.SEOUL);
    private static final LocalDateTime TOMORROW_AT = TODAY.plusDays(1).atTime(10, 0);
    private static final LocalDateTime TODAY_AT = TODAY.atTime(23, 30);

    @Autowired
    private ReservationService service;

    @Autowired
    private ReservationRepository reservations;

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
    private long mechanicA2;
    private long staffB;
    private long customerA;
    private long vehicleB;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소" + suffix);
        shopB = insertShop("B정비소" + suffix);
        ownerA = insertStaff(shopA, "t_oa_" + suffix, "박사장", "OWNER");
        mechanicA = insertStaff(shopA, "t_ma_" + suffix, "김정비", "MECHANIC");
        mechanicA2 = insertStaff(shopA, "t_mb_" + suffix, "이정비", "MECHANIC");
        staffB = insertStaff(shopB, "t_sb_" + suffix, "타정비소사람", "MECHANIC");
        customerA = insertCustomer(shopA, "김민수", "010-0000-0001");
        vehicleB = insertVehicle(shopB, insertCustomer(shopB, "박지영", "010-0000-0009"), "99나0009");
    }

    private LoginStaff owner() {
        return new LoginStaff(ownerA, shopA, StaffRole.OWNER);
    }

    private LoginStaff mechanic() {
        return new LoginStaff(mechanicA, shopA, StaffRole.MECHANIC);
    }

    private long reservation(String plate, LocalDateTime at, long assignee) {
        long vehicle = insertVehicle(shopA, customerA, plate);
        ReservationCreateResponse created = service.create(shopA, ownerA, new ReservationCreateRequest(vehicle, at, null, assignee));
        em.flush();
        em.clear();
        return created.reservationId();
    }

    private static ReservationUpdateRequest reassign(int version, long assigneeId, String reason) {
        return new ReservationUpdateRequest(version, null, null, assigneeId, reason);
    }

    private int revisionCount(long reservationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation_revision WHERE reservation_id = ?", Integer.class, reservationId);
    }

    private long assigneeOf(long reservationId) {
        return jdbc.queryForObject("SELECT assignee_staff_id FROM reservation WHERE id = ?", Long.class, reservationId);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    @DisplayName("R24(a) 전날까지 재배정: 담당이 바뀌고 version이 오르며, reservation_revision에 이전·이후 담당·사유·수정자·시각이 남는다")
    void reassignStoresTheRevisionRow() {
        long id = reservation("12가0001", TOMORROW_AT, ownerA);

        ReservationResponse response = service.update(owner(), id, reassign(0, mechanicA, "정비사 전담"));
        em.flush();
        em.clear();

        assertThat(response.version()).isEqualTo(1);
        assertThat(assigneeOf(id)).isEqualTo(mechanicA);
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT shop_id, kind, changed_by_staff_id, reason, prev_assignee_id, new_assignee_id, created_at "
                + "FROM reservation_revision WHERE reservation_id = ?", id);
        assertThat(((Number) row.get("shop_id")).longValue()).isEqualTo(shopA);
        assertThat(row.get("kind")).isEqualTo("ASSIGNEE");
        assertThat(((Number) row.get("changed_by_staff_id")).longValue()).isEqualTo(ownerA);
        assertThat(row.get("reason")).isEqualTo("정비사 전담");
        assertThat(((Number) row.get("prev_assignee_id")).longValue()).isEqualTo(ownerA);
        assertThat(((Number) row.get("new_assignee_id")).longValue()).isEqualTo(mechanicA);
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    @DisplayName("재배정을 두 번 하면 이력이 두 줄 쌓이고 두 번째의 이전 담당은 첫 번째의 이후 담당이다")
    void revisionsAccumulate() {
        long id = reservation("12가0002", TOMORROW_AT, ownerA);

        service.update(owner(), id, reassign(0, mechanicA, null));
        em.flush();
        em.clear();
        service.update(mechanic(), id, reassign(1, mechanicA2, "다시 변경"));
        em.flush();
        em.clear();

        assertThat(revisionCount(id)).isEqualTo(2);
        Map<String, Object> second = jdbc.queryForMap(
            "SELECT prev_assignee_id, new_assignee_id, changed_by_staff_id FROM reservation_revision "
                + "WHERE reservation_id = ? ORDER BY id DESC LIMIT 1", id);
        assertThat(((Number) second.get("prev_assignee_id")).longValue()).isEqualTo(mechanicA);
        assertThat(((Number) second.get("new_assignee_id")).longValue()).isEqualTo(mechanicA2);
        assertThat(((Number) second.get("changed_by_staff_id")).longValue()).isEqualTo(mechanicA);
    }

    @Test
    @DisplayName("R24(b)(c)(d) 당일: 정비사는 409, 사장은 사유 없으면 400·있으면 성공 — 역할은 토큰에서 온다 (HTTP)")
    void sameDayByRoleFromTheToken() throws Exception {
        long id = reservation("12가0003", TODAY_AT, ownerA);

        patchReassign(token(mechanicA, shopA, "MECHANIC"), id, "{\"version\":0,\"assigneeId\":" + mechanicA2 + ",\"reassignReason\":\"사유\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ASSIGNEE_CHANGE_CLOSED"));
        patchReassign(token(ownerA, shopA, "OWNER"), id, "{\"version\":0,\"assigneeId\":" + mechanicA + "}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("reassignReason"));
        assertThat(revisionCount(id)).isZero();
        assertThat(assigneeOf(id)).isEqualTo(ownerA);

        patchReassign(token(ownerA, shopA, "OWNER"), id, "{\"version\":0,\"assigneeId\":" + mechanicA + ",\"reassignReason\":\"당일 조정\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(revisionCount(id)).isEqualTo(1);
        assertThat(assigneeOf(id)).isEqualTo(mechanicA);
    }

    private org.springframework.test.web.servlet.ResultActions patchReassign(String token, long id, String body) throws Exception {
        return mvc.perform(patch("/api/reservations/" + id).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String token(long staffId, long shopId, String role) {
        return "Bearer " + TestJwts.sign(secret, TestJwts.staff(staffId, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    @Test
    @DisplayName("R24(f) 지난 날짜 예약은 사장도 409 ASSIGNEE_CHANGE_CLOSED, 담당·이력 그대로")
    void pastDateIsClosed() {
        long vehicle = insertVehicle(shopA, customerA, "12가0004");
        Reservation past = reservations.saveAndFlush(
            Reservation.register(shopA, vehicle, TODAY.minusDays(2).atTime(10, 0), false, null, ownerA, ownerA));
        em.clear();

        assertCode(() -> service.update(owner(), past.getId(), reassign(0, mechanicA, "사유")), ErrorCode.ASSIGNEE_CHANGE_CLOSED);

        assertThat(revisionCount(past.getId())).isZero();
        assertThat(assigneeOf(past.getId())).isEqualTo(ownerA);
    }

    @Test
    @DisplayName("재배정이 겹침(R18)으로 실패하면 담당도 이력도 바뀌지 않는다")
    void failedReassignLeavesNoRevision() {
        long id = reservation("12가0005", TOMORROW_AT, ownerA);
        long busy = reservation("12가0006", TOMORROW_AT, mechanicA); // 같은 시각, 다른 담당

        assertThatThrownBy(() -> service.update(owner(), id, reassign(0, mechanicA, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.MECHANIC_SLOT_CONFLICT);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", busy));
            });

        assertThat(revisionCount(id)).isZero();
        assertThat(assigneeOf(id)).isEqualTo(ownerA);
    }

    @Test
    @DisplayName("X13·X14 다른 정비소 정비사를 담당으로 → 400 INVALID_INPUT(assigneeId), 다른 정비소 예약 id → 404, 이력 없음")
    void isolation() {
        long id = reservation("12가0007", TOMORROW_AT, ownerA);
        Reservation other = reservations.saveAndFlush(
            Reservation.register(shopB, vehicleB, TODAY.plusDays(4).atTime(10, 0), false, null, staffB, staffB));
        em.clear();

        assertThatThrownBy(() -> service.update(owner(), id, reassign(0, staffB, "사유")))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting("field").containsExactly("assigneeId");
            });
        assertCode(() -> service.update(owner(), other.getId(), reassign(0, mechanicA, null)), ErrorCode.NOT_FOUND);

        assertThat(revisionCount(id)).isZero();
        assertThat(revisionCount(other.getId())).isZero();
        assertThat(assigneeOf(other.getId())).isEqualTo(staffB);
    }

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
