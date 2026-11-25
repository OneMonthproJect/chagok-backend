package com.wanted.chagok.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanted.chagok.domain.account.api.StaffReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** account.api StaffReader.findNames: 한 번의 조회, 정비소 격리(07 I-3). {@code ./gradlew dbTest}. */
@Tag("db")
@SpringBootTest
@Transactional
class StaffReaderDbTest {

    @Autowired
    private StaffReader staffReader;

    @Autowired
    private JdbcTemplate jdbc;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private long shopId;
    private long otherShopId;
    private long ownerId;
    private long mechanicId;
    private long otherShopStaffId;

    @BeforeEach
    void insertData() {
        shopId = insertShop("테스트카센터" + suffix);
        otherShopId = insertShop("다른카센터" + suffix);
        ownerId = insertStaff(shopId, "t_o_" + suffix, "박사장", "OWNER");
        mechanicId = insertStaff(shopId, "t_m_" + suffix, "김정비", "MECHANIC");
        otherShopStaffId = insertStaff(otherShopId, "t_x_" + suffix, "타정비소사람", "MECHANIC");
    }

    @Test
    @DisplayName("같은 정비소 staff의 이름을 한 번에 돌려준다")
    void findsNames() {
        assertThat(staffReader.findNames(shopId, List.of(ownerId, mechanicId)))
            .containsEntry(ownerId, "박사장")
            .containsEntry(mechanicId, "김정비")
            .hasSize(2);
    }

    @Test
    @DisplayName("다른 정비소 staff는 결과에서 빠진다 (격리)")
    void excludesOtherShopStaff() {
        assertThat(staffReader.findNames(shopId, List.of(mechanicId, otherShopStaffId)))
            .containsOnlyKeys(mechanicId);
        assertThat(staffReader.findNames(otherShopId, List.of(mechanicId))).isEmpty();
    }

    @Test
    @DisplayName("없는 id는 결과에서 빠지고 오류가 아니다")
    void excludesMissingIds() {
        assertThat(staffReader.findNames(shopId, List.of(mechanicId, -1L))).containsOnlyKeys(mechanicId);
    }

    @Test
    @DisplayName("빈 입력은 빈 Map, 중복 id는 한 번만")
    void emptyAndDuplicates() {
        assertThat(staffReader.findNames(shopId, List.of())).isEmpty();
        assertThat(staffReader.findNames(shopId, null)).isEmpty();
        assertThat(staffReader.findNames(shopId, List.of(mechanicId, mechanicId))).hasSize(1);
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
