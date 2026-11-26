package com.wanted.chagok.domain.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.global.auth.TestJwts;
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
 * API 36 {@code GET /api/staff/assignees}를 실제 보안 필터·DB로 확인한다 (08 7.1, X16 서비스 관리자 403, 07 I-3 정비소 격리).
 * 앱 전체가 뜨므로 MySQL이 필요하다({@code ./gradlew dbTest}). 토큰은 실행 중인 앱의 {@code chagok.jwt.secret}으로 서명한다.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffEndpointDbTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${chagok.jwt.secret}")
    private String secret;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final Instant later = Instant.now().plusSeconds(3_600);
    private long shopA;
    private long shopB;
    private long ownerA;
    private long mechanicA;
    private long staffB;

    @BeforeEach
    void insertData() {
        shopA = insertShop("가카센터" + suffix);
        shopB = insertShop("나카센터" + suffix);
        ownerA = insertStaff(shopA, "ea_" + suffix, "박사장", "OWNER");
        mechanicA = insertStaff(shopA, "ma_" + suffix, "김정비", "MECHANIC");
        staffB = insertStaff(shopB, "mb_" + suffix, "타정비소사람", "MECHANIC");
    }

    private String token(long staffId, long shopId, String role) {
        return "Bearer " + TestJwts.sign(secret, TestJwts.staff(staffId, shopId, role, later));
    }

    @Test
    @DisplayName("토큰 없음 → 401 AUTH_REQUIRED, 서비스 관리자 토큰 → 403 FORBIDDEN (X16)")
    void anonymousAndAdminAreRejected() throws Exception {
        mvc.perform(get("/api/staff/assignees"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/staff/assignees").header("Authorization", "Bearer " + TestJwts.sign(secret, TestJwts.admin(1, later))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("사장·정비사 모두 부를 수 있고, 자기 정비소 직원만 id 순서·colorIndex와 함께 받는다")
    void ownerAndMechanicSeeOwnShopOnly() throws Exception {
        for (String auth : new String[] {token(ownerA, shopA, "OWNER"), token(mechanicA, shopA, "MECHANIC")}) {
            mvc.perform(get("/api/staff/assignees").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].staffId").value(ownerA))
                .andExpect(jsonPath("$.data.items[0].name").value("박사장"))
                .andExpect(jsonPath("$.data.items[0].role").value("OWNER"))
                .andExpect(jsonPath("$.data.items[0].colorIndex").value(0))
                .andExpect(jsonPath("$.data.items[1].staffId").value(mechanicA))
                .andExpect(jsonPath("$.data.items[1].role").value("MECHANIC"))
                .andExpect(jsonPath("$.data.items[1].colorIndex").value(1));
        }
    }

    @Test
    @DisplayName("격리: A 토큰으로 B 정비소 직원이 나오지 않고, shopId 쿼리로 B를 지정해도 무시한다")
    void otherShopStaffNeverAppears() throws Exception {
        mvc.perform(get("/api/staff/assignees").param("shopId", String.valueOf(shopB))
                .header("Authorization", token(ownerA, shopA, "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[?(@.staffId == " + staffB + ")]").isEmpty());
        mvc.perform(get("/api/staff/assignees").header("Authorization", token(staffB, shopB, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].staffId").value(staffB))
            .andExpect(jsonPath("$.data.items[0].colorIndex").value(0));
    }

    private long insertShop(String name) {
        jdbc.update("INSERT INTO shop (name, phone, created_at) VALUES (?, ?, ?)", name, "02-000-0000", LocalDateTime.now());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertStaff(long shop, String loginId, String name, String role) {
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            shop, loginId, "x", name, role, LocalDateTime.now());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
