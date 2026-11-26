package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지난 예약 정정(API 40)과 이력 조회(API 41)를 실제 표(V1·V2)·실제 account 구현·실제 보안 필터로 확인한다 (08 7.1 40·41번, R25~R29, X14):
 * STATUS·MEMO 이력 행이 V2 CHECK를 지나 저장되는 것, 토큰의 역할로 가려지는 403(정비사의 상태 정정·담당이 아닌 정비사·기간 4일째),
 * 허용표 밖이면 이력이 남지 않는 것, 41의 순서·이름·빈 목록, 다른 정비소 404. 앱 전체가 뜨므로 MySQL이 필요하다
 * ({@code ./gradlew dbTest}). 정비소·계정·고객·차량은 이 테스트가 넣고 롤백한다. 날짜는 실제 오늘({@code Asia/Seoul}) 기준이다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReservationCorrectionDbTest {

    private static final LocalDate TODAY = LocalDate.now(ClockConfig.SEOUL);

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
    private long assigneeA;
    private long otherMechanicA;
    private long staffB;
    private long customerA;
    private long vehicleB;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소" + suffix);
        shopB = insertShop("B정비소" + suffix);
        ownerA = insertStaff(shopA, "t_oa_" + suffix, "박사장", "OWNER");
        assigneeA = insertStaff(shopA, "t_ma_" + suffix, "김정비", "MECHANIC");
        otherMechanicA = insertStaff(shopA, "t_mb_" + suffix, "이정비", "MECHANIC");
        staffB = insertStaff(shopB, "t_sb_" + suffix, "타정비소사람", "MECHANIC");
        customerA = insertCustomer(shopA, "김민수", "010-0000-0001");
        vehicleB = insertVehicle(shopB, insertCustomer(shopB, "박지영", "010-0000-0009"), "99나0009");
    }

    private String ownerToken() {
        return token(ownerA, shopA, "OWNER");
    }

    private String token(long staffId, long shopId, String role) {
        return "Bearer " + TestJwts.sign(secret, TestJwts.staff(staffId, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    /** 담당이 {@code assigneeA}인 정비소 A의 끝난 예약. 예약 날짜는 오늘로부터 {@code daysAgo}일 전이다. */
    private long finished(String plate, int daysAgo, String status, String memo) {
        long vehicle = insertVehicle(shopA, customerA, plate);
        Reservation saved = reservations.saveAndFlush(
            Reservation.register(shopA, vehicle, TODAY.minusDays(daysAgo).atTime(10, 0), false, memo, assigneeA, ownerA));
        jdbc.update("UPDATE reservation SET status = ? WHERE id = ?", status, saved.getId());
        em.clear();
        return saved.getId();
    }

    private ResultActions correct(String token, long id, String body) throws Exception {
        return mvc.perform(post("/api/reservations/" + id + "/correct").header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int revisionCount(long reservationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation_revision WHERE reservation_id = ?", Integer.class, reservationId);
    }

    private String statusOf(long reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId);
    }

    @Test
    @DisplayName("R25 사장이 노쇼를 취소로 정정: 200·version +1, STATUS 이력 행이 V2 CHECK를 지나 이전·이후 상태·사유·수정자와 함께 저장된다")
    void ownerCorrectsStatusAndTheRowIsStored() throws Exception {
        long id = finished("12가0001", 5, "NO_SHOW", null);

        correct(ownerToken(), id, "{\"version\":0,\"status\":\"CANCELED\",\"reason\":\"고객이 전날 전화로 취소함\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELED"))
            .andExpect(jsonPath("$.data.version").value(1));
        em.clear();

        assertThat(statusOf(id)).isEqualTo("CANCELED");
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT shop_id, kind, changed_by_staff_id, reason, prev_status, new_status, prev_memo, new_memo, prev_assignee_id, "
                + "new_assignee_id, created_at FROM reservation_revision WHERE reservation_id = ?", id);
        assertThat(((Number) row.get("shop_id")).longValue()).isEqualTo(shopA);
        assertThat(row.get("kind")).isEqualTo("STATUS");
        assertThat(((Number) row.get("changed_by_staff_id")).longValue()).isEqualTo(ownerA);
        assertThat(row.get("reason")).isEqualTo("고객이 전날 전화로 취소함");
        assertThat(row.get("prev_status")).isEqualTo("NO_SHOW");
        assertThat(row.get("new_status")).isEqualTo("CANCELED");
        assertThat(row.get("prev_memo")).isNull();
        assertThat(row.get("new_memo")).isNull();
        assertThat(row.get("prev_assignee_id")).isNull();
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    @DisplayName("상태와 메모를 함께 정정하면 STATUS·MEMO 두 행이 같은 사유로 저장되고 version은 한 번만 오른다")
    void statusAndMemoRows() throws Exception {
        long id = finished("12가0002", 5, "CANCELED", "기존 메모");

        correct(ownerToken(), id, "{\"version\":0,\"status\":\"COMPLETED\",\"purposeMemo\":\"점검 후 완료\",\"reason\":\"사후 정정\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1));
        em.clear();

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT kind, reason, prev_status, new_status, prev_memo, new_memo FROM reservation_revision "
                + "WHERE reservation_id = ? ORDER BY id", id);
        assertThat(rows).extracting(r -> r.get("kind")).containsExactly("STATUS", "MEMO");
        assertThat(rows).extracting(r -> r.get("reason")).containsOnly("사후 정정");
        assertThat(rows.get(0).get("new_status")).isEqualTo("COMPLETED");
        assertThat(rows.get(1).get("prev_memo")).isEqualTo("기존 메모");
        assertThat(rows.get(1).get("new_memo")).isEqualTo("점검 후 완료");
    }

    @Test
    @DisplayName("R26 완료→취소는 사장도 409 INVALID_STATUS_TRANSITION, 함께 보낸 메모도 바뀌지 않고 이력도 없다")
    void forbiddenTransitionLeavesNothing() throws Exception {
        long id = finished("12가0003", 5, "COMPLETED", "기존 메모");

        correct(ownerToken(), id, "{\"version\":0,\"status\":\"CANCELED\",\"purposeMemo\":\"새 메모\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
        em.clear();

        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT purpose_memo FROM reservation WHERE id = ?", String.class, id)).isEqualTo("기존 메모");
        assertThat(revisionCount(id)).isZero();
    }

    @Test
    @DisplayName("R27·R29 정비사의 상태 정정은 403 FORBIDDEN(담당이어도), 담당이 아닌 정비사의 메모 정정도 403 — 역할은 토큰에서 온다")
    void roleFromTheToken() throws Exception {
        long id = finished("12가0004", 1, "NO_SHOW", "기존");

        correct(token(assigneeA, shopA, "MECHANIC"), id, "{\"version\":0,\"status\":\"CANCELED\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        correct(token(otherMechanicA, shopA, "MECHANIC"), id, "{\"version\":0,\"purposeMemo\":\"고침\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(statusOf(id)).isEqualTo("NO_SHOW");
        assertThat(revisionCount(id)).isZero();
    }

    @Test
    @DisplayName("R28 담당 정비사의 메모 정정: 예약 날짜 + 3일째 200(MEMO 이력), 4일째 403 EDIT_PERIOD_EXPIRED, 사장은 4일째도 200")
    void memoPeriod() throws Exception {
        long within = finished("12가0005", 3, "COMPLETED", "기존");
        long expired = finished("12가0006", 4, "COMPLETED", "기존");
        String mechanic = token(assigneeA, shopA, "MECHANIC");

        correct(mechanic, within, "{\"version\":0,\"purposeMemo\":\"담당이 고침\",\"reason\":\"통화 반영\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(revisionCount(within)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT kind FROM reservation_revision WHERE reservation_id = ?", String.class, within))
            .isEqualTo("MEMO");

        correct(mechanic, expired, "{\"version\":0,\"purposeMemo\":\"늦게 고침\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("EDIT_PERIOD_EXPIRED"));
        assertThat(revisionCount(expired)).isZero();

        correct(ownerToken(), expired, "{\"version\":0,\"purposeMemo\":\"사장이 고침\"}").andExpect(status().isOk());
        assertThat(revisionCount(expired)).isEqualTo(1);
    }

    @Test
    @DisplayName("41 이력을 시간순으로, 바꾼 사람과 담당 이름을 채워 주고, 이력이 없는 예약은 items []")
    void revisionsAreListedInOrderWithNames() throws Exception {
        long id = finished("12가0007", 5, "NO_SHOW", null);
        long empty = finished("12가0008", 5, "COMPLETED", null);
        jdbc.update("INSERT INTO reservation_revision (shop_id, reservation_id, kind, changed_by_staff_id, reason, "
            + "prev_assignee_id, new_assignee_id, created_at) VALUES (?, ?, 'ASSIGNEE', ?, NULL, ?, ?, ?)",
            shopA, id, ownerA, otherMechanicA, assigneeA, LocalDateTime.now().minusDays(6));
        correct(ownerToken(), id, "{\"version\":0,\"status\":\"CANCELED\",\"purposeMemo\":\"정정 메모\",\"reason\":\"사후 정정\"}")
            .andExpect(status().isOk());
        em.flush();
        em.clear();

        mvc.perform(get("/api/reservations/" + id + "/revisions").header("Authorization", token(assigneeA, shopA, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].kind").value("ASSIGNEE"))
            .andExpect(jsonPath("$.data.items[0].changedBy.name").value("박사장"))
            .andExpect(jsonPath("$.data.items[0].changes[0].before.name").value("이정비"))
            .andExpect(jsonPath("$.data.items[0].changes[0].after.name").value("김정비"))
            .andExpect(jsonPath("$.data.items[1].kind").value("STATUS"))
            .andExpect(jsonPath("$.data.items[1].reason").value("사후 정정"))
            .andExpect(jsonPath("$.data.items[1].changes[0].before").value("NO_SHOW"))
            .andExpect(jsonPath("$.data.items[1].changes[0].after").value("CANCELED"))
            .andExpect(jsonPath("$.data.items[2].kind").value("MEMO"))
            .andExpect(jsonPath("$.data.items[2].changes[0].after").value("정정 메모"));
        mvc.perform(get("/api/reservations/" + empty + "/revisions").header("Authorization", ownerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("X14 다른 정비소 예약의 정정(40)·이력(41)은 404, B 예약·이력은 그대로")
    void isolation() throws Exception {
        Reservation other = reservations.saveAndFlush(
            Reservation.register(shopB, vehicleB, TODAY.minusDays(5).atTime(10, 0), false, "B 메모", staffB, staffB));
        jdbc.update("UPDATE reservation SET status = 'NO_SHOW' WHERE id = ?", other.getId());
        em.clear();

        correct(ownerToken(), other.getId(), "{\"version\":0,\"status\":\"CANCELED\"}")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/reservations/" + other.getId() + "/revisions").header("Authorization", ownerToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(statusOf(other.getId())).isEqualTo("NO_SHOW");
        assertThat(revisionCount(other.getId())).isZero();
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
