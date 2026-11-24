package com.wanted.chagok.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.account.dto.AdminLoginResponse;
import com.wanted.chagok.domain.account.dto.LoginRequest;
import com.wanted.chagok.domain.account.dto.LoginResponse;
import com.wanted.chagok.domain.account.dto.MeResponse;
import com.wanted.chagok.domain.account.service.AuthService;
import com.wanted.chagok.global.auth.LoginAdmin;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.auth.TokenClaims;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 표(staff·platform_admin·shop, V1)에서 로그인·내 계정이 동작하는지 본다 (04 F-AUTH-01·02, 08 01~03). 앱 전체가 뜨고
 * Flyway·JPA validate를 거치므로 MySQL이 필요하다: {@code ./gradlew dbTest}(CI db-test, 빈 MySQL).
 *
 * <p>테스트 계정은 이 클래스가 직접 만들고(시연 시드 {@code demo_seed.sql}에 기대지 않는다), 테스트마다 트랜잭션을 롤백해
 * 행이 남지 않는다. 아이디는 매번 달라 개발 DB에 시드 계정이 있어도 겹치지 않는다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class AuthServiceDbTest {

    private static final String PASSWORD = "1234";

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String ownerLoginId = "t_owner_" + suffix;
    private final String mechanicLoginId = "t_mech_" + suffix;
    private final String adminLoginId = "t_admin_" + suffix;

    private long shopId;
    private long otherShopId;
    private long ownerId;
    private long mechanicId;
    private long adminId;

    @BeforeEach
    void insertAccounts() {
        shopId = insertShop("테스트카센터" + suffix);
        otherShopId = insertShop("다른카센터" + suffix);
        ownerId = insertStaff(shopId, ownerLoginId, "박사장", "OWNER");
        mechanicId = insertStaff(shopId, mechanicLoginId, "김정비", "MECHANIC");
        adminId = insertAdmin(adminLoginId, "운영관리자");
    }

    private long insertShop(String name) {
        jdbc.update("INSERT INTO shop (name, phone, created_at) VALUES (?, ?, ?)", name, "02-000-0000", LocalDateTime.now());
        return lastInsertId();
    }

    private long insertStaff(long shop, String loginId, String name, String role) {
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            shop, loginId, passwordEncoder.encode(PASSWORD), name, role, LocalDateTime.now());
        return lastInsertId();
    }

    private long insertAdmin(String loginId, String name) {
        jdbc.update("INSERT INTO platform_admin (login_id, password_hash, name, created_at) VALUES (?, ?, ?, ?)",
            loginId, passwordEncoder.encode(PASSWORD), name, LocalDateTime.now());
        return lastInsertId();
    }

    private long lastInsertId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    @DisplayName("01 사장 로그인: 실제 표에서 읽은 정비소명·이름·역할, 토큰은 그 계정·정비소·역할")
    void ownerLogin() {
        LoginResponse response = authService.login(new LoginRequest(ownerLoginId, PASSWORD));

        assertThat(response.staff()).isEqualTo(new MeResponse(ownerId, "박사장", StaffRole.OWNER, shopId, "테스트카센터" + suffix));
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(43_200);
        Jwt jwt = jwtDecoder.decode(response.accessToken());
        assertThat(TokenClaims.principalOf(jwt)).contains(new LoginStaff(ownerId, shopId, StaffRole.OWNER));
    }

    @Test
    @DisplayName("01 정비사 로그인: 토큰 role = MECHANIC")
    void mechanicLogin() {
        LoginResponse response = authService.login(new LoginRequest(mechanicLoginId, PASSWORD));

        assertThat(TokenClaims.principalOf(jwtDecoder.decode(response.accessToken())))
            .contains(new LoginStaff(mechanicId, shopId, StaffRole.MECHANIC));
    }

    @Test
    @DisplayName("01 비밀번호 틀림·없는 아이디 → 둘 다 LOGIN_FAILED")
    void loginFailures() {
        assertCode(() -> authService.login(new LoginRequest(ownerLoginId, "wrong")), ErrorCode.LOGIN_FAILED);
        assertCode(() -> authService.login(new LoginRequest("t_nobody_" + suffix, PASSWORD)), ErrorCode.LOGIN_FAILED);
        assertCode(() -> authService.login(new LoginRequest(ownerLoginId, "a".repeat(100))), ErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("02 서비스 관리자 로그인: admin.role = ADMIN, 토큰에 shopId 없음 (07 I-5)")
    void adminLogin() {
        AdminLoginResponse response = authService.adminLogin(new LoginRequest(adminLoginId, PASSWORD));

        assertThat(response.admin()).isEqualTo(new AdminLoginResponse.AdminInfo(adminId, "운영관리자", "ADMIN"));
        Jwt jwt = jwtDecoder.decode(response.accessToken());
        assertThat(jwt.getClaims()).doesNotContainKey(TokenClaims.SHOP_ID);
        assertThat(TokenClaims.principalOf(jwt)).contains(new LoginAdmin(adminId));
    }

    @Test
    @DisplayName("계정 표가 다르다: 사장·정비사 아이디는 02로, 서비스 관리자 아이디는 01로 로그인할 수 없다 (LOGIN_FAILED)")
    void accountsAreSeparatedByLoginApi() {
        assertCode(() -> authService.adminLogin(new LoginRequest(ownerLoginId, PASSWORD)), ErrorCode.LOGIN_FAILED);
        assertCode(() -> authService.adminLogin(new LoginRequest(mechanicLoginId, PASSWORD)), ErrorCode.LOGIN_FAILED);
        assertCode(() -> authService.login(new LoginRequest(adminLoginId, PASSWORD)), ErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("03 내 계정: 토큰의 staffId·shopId로 찾고 정비소명을 돌려준다")
    void me() {
        MeResponse me = authService.me(new LoginStaff(mechanicId, shopId, StaffRole.MECHANIC));

        assertThat(me).isEqualTo(new MeResponse(mechanicId, "김정비", StaffRole.MECHANIC, shopId, "테스트카센터" + suffix));
    }

    @Test
    @DisplayName("03 다른 정비소 번호로는 그 계정을 찾을 수 없다 (정비소 격리: shopId 조건)")
    void meIsScopedToShop() {
        assertCode(() -> authService.me(new LoginStaff(ownerId, otherShopId, StaffRole.OWNER)), ErrorCode.AUTH_REQUIRED);
    }
}
