package com.wanted.chagok.domain.reservation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 응답의 담당 정비사 {@code assignee { staffId, name, colorIndex }}를 실제 표·보안 필터·실제 account 구현으로 확인한다
 * (08 15·16·19번, P0-나머지, #89). 15(목록)·16(등록)·19(변경) 응답의 값이 담당 후보 API 36의 값과 같은지, 다른 정비소 직원이
 * 섞이지 않는지 본다. 17(패널)·04(홈)는 시드 HTTP 테스트({@code PanelSeedHttpDbTest}, {@code HomeSeedHttpDbTest})가 본다.
 * 앱 전체가 뜨므로 MySQL이 필요하다({@code ./gradlew dbTest}). 정비소·계정·고객·차량은 이 테스트가 넣고 롤백한다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReservationAssigneeResponseDbTest {

    private static final LocalDate TODAY = LocalDate.now(ClockConfig.SEOUL);
    private static final LocalDateTime SLOT = TODAY.plusDays(3).atTime(10, 0);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationRepository reservations;

    @Value("${chagok.jwt.secret}")
    private String secret;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private long shopA;
    private long shopB;
    private long ownerA;
    private long mechanicA;
    private long staffB;
    private long vehicleA1;
    private long vehicleA2;
    private long vehicleB;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소" + suffix);
        shopB = insertShop("B정비소" + suffix);
        ownerA = insertStaff(shopA, "t_oa_" + suffix, "박사장", "OWNER");
        mechanicA = insertStaff(shopA, "t_ma_" + suffix, "김정비", "MECHANIC");
        staffB = insertStaff(shopB, "t_sb_" + suffix, "최사장", "OWNER");
        long customerA = insertCustomer(shopA, "김민수", "010-0000-0001");
        long customerB = insertCustomer(shopB, "박지영", "010-0000-0009");
        vehicleA1 = insertVehicle(shopA, customerA, "12가0001");
        vehicleA2 = insertVehicle(shopA, customerA, "12가0002");
        vehicleB = insertVehicle(shopB, customerB, "99나0009");
    }

    private String token(long staffId, long shopId, String role) {
        return "Bearer " + TestJwts.sign(secret, TestJwts.staff(staffId, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    @Test
    @DisplayName("15 목록의 assignee: 예약마다 그 담당의 이름·colorIndex(사장 0, 정비사 1)가 실리고, 다른 정비소 직원은 섞이지 않는다")
    void listCarriesAssignee() throws Exception {
        reservations.saveAndFlush(Reservation.register(shopA, vehicleA1, SLOT, false, null, mechanicA, ownerA));
        reservations.saveAndFlush(Reservation.register(shopA, vehicleA2, SLOT.plusHours(1), false, null, ownerA, ownerA));
        reservations.saveAndFlush(Reservation.register(shopB, vehicleB, SLOT, false, null, staffB, staffB));

        mvc.perform(get("/api/reservations").param("from", TODAY.plusDays(3).toString()).param("to", TODAY.plusDays(3).toString())
                .header("Authorization", token(ownerA, shopA, "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].assignee.staffId").value(mechanicA))
            .andExpect(jsonPath("$.data.items[0].assignee.name").value("김정비"))
            .andExpect(jsonPath("$.data.items[0].assignee.colorIndex").value(1))
            .andExpect(jsonPath("$.data.items[1].assignee.staffId").value(ownerA))
            .andExpect(jsonPath("$.data.items[1].assignee.name").value("박사장"))
            .andExpect(jsonPath("$.data.items[1].assignee.colorIndex").value(0));
        mvc.perform(get("/api/reservations").param("from", TODAY.plusDays(3).toString()).param("to", TODAY.plusDays(3).toString())
                .header("Authorization", token(staffB, shopB, "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].assignee.name").value("최사장"))
            .andExpect(jsonPath("$.data.items[0].assignee.colorIndex").value(0));
    }

    @Test
    @DisplayName("assignee의 colorIndex는 담당 후보 API 36의 값과 같다 (계산은 account 한 곳)")
    void colorIndexMatchesApi36() throws Exception {
        reservations.saveAndFlush(Reservation.register(shopA, vehicleA1, SLOT, false, null, mechanicA, ownerA));
        String auth = token(ownerA, shopA, "OWNER");

        mvc.perform(get("/api/staff/assignees").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[?(@.staffId == " + mechanicA + ")].colorIndex").value(1));
        mvc.perform(get("/api/reservations").param("from", TODAY.plusDays(3).toString()).param("to", TODAY.plusDays(3).toString())
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].assignee.colorIndex").value(1));
    }

    @Test
    @DisplayName("16 등록 응답에 assignee가 실린다: 사장이 정비사를 담당으로 등록하면 응답 assignee는 정비사")
    void createResponseCarriesAssignee() throws Exception {
        mvc.perform(post("/api/reservations").header("Authorization", token(ownerA, shopA, "OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleId\":" + vehicleA1 + ",\"reservedAt\":\"" + SLOT + ":00\",\"assigneeId\":" + mechanicA + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reservationId").isNumber())
            .andExpect(jsonPath("$.data.assignee.staffId").value(mechanicA))
            .andExpect(jsonPath("$.data.assignee.name").value("김정비"))
            .andExpect(jsonPath("$.data.assignee.colorIndex").value(1));
    }

    @Test
    @DisplayName("19 변경 응답에 그 예약의 담당 assignee가 실리고, 18 방문 완료 응답에는 실리지 않는다")
    void updateResponseCarriesAssigneeButCompleteDoesNot() throws Exception {
        long id = reservations.saveAndFlush(Reservation.register(shopA, vehicleA1, SLOT, false, null, mechanicA, ownerA)).getId();
        String auth = token(ownerA, shopA, "OWNER");

        mvc.perform(patch("/api/reservations/" + id).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"purpose\":{\"memo\":\"소음\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.assignee.staffId").value(mechanicA))
            .andExpect(jsonPath("$.data.assignee.name").value("김정비"))
            .andExpect(jsonPath("$.data.assignee.colorIndex").value(1));
        mvc.perform(post("/api/reservations/" + id + "/complete").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignee").doesNotExist());
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
