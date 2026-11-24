package com.wanted.chagok.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.dto.AdminLoginResponse;
import com.wanted.chagok.domain.account.dto.LoginRequest;
import com.wanted.chagok.domain.account.dto.LoginResponse;
import com.wanted.chagok.domain.account.dto.MeResponse;
import com.wanted.chagok.domain.account.entity.PlatformAdmin;
import com.wanted.chagok.domain.account.entity.Staff;
import com.wanted.chagok.domain.account.repository.PlatformAdminRepository;
import com.wanted.chagok.domain.account.repository.StaffRepository;
import com.wanted.chagok.domain.account.service.AuthService;
import com.wanted.chagok.domain.shop.api.ShopReader;
import com.wanted.chagok.domain.shop.api.ShopRef;
import com.wanted.chagok.global.auth.JwtIssuer;
import com.wanted.chagok.global.auth.JwtVerification;
import com.wanted.chagok.global.auth.LoginAdmin;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.auth.TokenClaims;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 로그인 01·02, 내 계정 03의 업무 규칙 (04 F-AUTH-01·02, 08 01~03). Repository·정비소 조회는 가짜, 비밀번호 해시·토큰은 실제 구현을 쓴다.
 * DB 없이 돈다. DB에서 실제 표를 읽는 확인은 {@code AuthServiceDbTest}, URL 권한은 {@code EndpointGuardTest}.
 */
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T01:00:00Z");

    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final PlatformAdminRepository adminRepository = mock(PlatformAdminRepository.class);
    private final ShopReader shopReader = mock(ShopReader.class);
    /** 서비스가 쓰는 인코더(호출 횟수를 확인하려고 spy). 테스트 데이터의 해시는 스텁 도중 호출해도 안전한 별도 인코더로 만든다. */
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
    private final PasswordEncoder hasher = new BCryptPasswordEncoder();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final String secret = TestJwts.randomSecret();
    private final JwtDecoder decoder = JwtVerification.decoder(secret, clock);

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(staffRepository, adminRepository, shopReader, encoder, new JwtIssuer(secret, clock));
        when(shopReader.findById(1L)).thenReturn(Optional.of(new ShopRef(1L, "차곡카센터")));
    }

    private Staff staff(long id, long shopId, String loginId, String rawPassword, String name, StaffRole role) {
        Staff staff = BeanUtils.instantiateClass(Staff.class);
        ReflectionTestUtils.setField(staff, "id", id);
        ReflectionTestUtils.setField(staff, "shopId", shopId);
        ReflectionTestUtils.setField(staff, "loginId", loginId);
        ReflectionTestUtils.setField(staff, "passwordHash", hasher.encode(rawPassword));
        ReflectionTestUtils.setField(staff, "name", name);
        ReflectionTestUtils.setField(staff, "role", role);
        return staff;
    }

    private PlatformAdmin admin(long id, String loginId, String rawPassword, String name) {
        PlatformAdmin admin = BeanUtils.instantiateClass(PlatformAdmin.class);
        ReflectionTestUtils.setField(admin, "id", id);
        ReflectionTestUtils.setField(admin, "loginId", loginId);
        ReflectionTestUtils.setField(admin, "passwordHash", hasher.encode(rawPassword));
        ReflectionTestUtils.setField(admin, "name", name);
        return admin;
    }

    private void givenOwner() {
        when(staffRepository.findByLoginId("owner_a"))
            .thenReturn(Optional.of(staff(1, 1, "owner_a", "1234", "박사장", StaffRole.OWNER)));
    }

    private static void assertLoginFailed(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LOGIN_FAILED));
    }

    // ---------- 01 로그인 ----------

    @Test
    @DisplayName("01 사장 로그인 성공: 08 응답 값(Bearer, 43200, staff)과 규약대로의 토큰")
    void ownerLogin() {
        givenOwner();

        LoginResponse response = service.login(new LoginRequest("owner_a", "1234"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(43_200);
        assertThat(response.staff()).isEqualTo(new MeResponse(1, "박사장", StaffRole.OWNER, 1, "차곡카센터"));
        Jwt jwt = decoder.decode(response.accessToken());
        assertThat(TokenClaims.principalOf(jwt)).contains(new LoginStaff(1, 1, StaffRole.OWNER));
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(43_200));
    }

    @Test
    @DisplayName("01 정비사 로그인 성공: 토큰 role = MECHANIC")
    void mechanicLogin() {
        when(staffRepository.findByLoginId("mech_a"))
            .thenReturn(Optional.of(staff(2, 1, "mech_a", "1234", "김정비", StaffRole.MECHANIC)));

        LoginResponse response = service.login(new LoginRequest("mech_a", "1234"));

        assertThat(response.staff().role()).isEqualTo(StaffRole.MECHANIC);
        assertThat(TokenClaims.principalOf(decoder.decode(response.accessToken())))
            .contains(new LoginStaff(2, 1, StaffRole.MECHANIC));
    }

    @Test
    @DisplayName("01 비밀번호 틀림 → LOGIN_FAILED")
    void wrongPassword() {
        givenOwner();

        assertLoginFailed(() -> service.login(new LoginRequest("owner_a", "wrong")));
    }

    @Test
    @DisplayName("01 없는 아이디 → 같은 LOGIN_FAILED, 그리고 비밀번호 비교를 한 번 한다 (응답 시간으로 아이디 존재가 드러나지 않게)")
    void unknownLoginIdIsSameFailureAndStillComparesHash() {
        when(staffRepository.findByLoginId("nobody")).thenReturn(Optional.empty());

        assertLoginFailed(() -> service.login(new LoginRequest("nobody", "1234")));

        verify(encoder).matches(eq("1234"), anyString());
    }

    @Test
    @DisplayName("01 아이디 없음과 비밀번호 틀림은 응답이 같다 (코드·메시지·상태)")
    void bothFailuresLookIdentical() {
        givenOwner();
        when(staffRepository.findByLoginId("nobody")).thenReturn(Optional.empty());

        BusinessException wrongPassword = catchBusiness(() -> service.login(new LoginRequest("owner_a", "wrong")));
        BusinessException unknownId = catchBusiness(() -> service.login(new LoginRequest("nobody", "wrong")));

        assertThat(unknownId.code()).isEqualTo(wrongPassword.code());
        assertThat(unknownId.code().status()).isEqualTo(wrongPassword.code().status());
        assertThat(unknownId.code().message()).isEqualTo(wrongPassword.code().message());
        assertThat(unknownId.details()).isEqualTo(wrongPassword.details());
        assertThat(unknownId.fieldErrors()).isEqualTo(wrongPassword.fieldErrors());
    }

    @Test
    @DisplayName("01 입력 상한(비밀번호 100자, 한글 100자=300바이트)에서도 오류 없이 LOGIN_FAILED (BCrypt 72바이트 한계)")
    void veryLongPasswordIsJustWrong() {
        givenOwner();
        when(staffRepository.findByLoginId("nobody")).thenReturn(Optional.empty());

        assertLoginFailed(() -> service.login(new LoginRequest("owner_a", "a".repeat(100))));
        assertLoginFailed(() -> service.login(new LoginRequest("owner_a", "가".repeat(100))));
        assertLoginFailed(() -> service.login(new LoginRequest("nobody", "a".repeat(100))));
    }

    @Test
    @DisplayName("01로 서비스 관리자 계정은 로그인할 수 없다 (표가 다르다: staff에 없으면 LOGIN_FAILED)")
    void adminCredentialsCannotUseStaffLogin() {
        when(staffRepository.findByLoginId("admin")).thenReturn(Optional.empty());

        assertLoginFailed(() -> service.login(new LoginRequest("admin", "1234")));
        verify(adminRepository, never()).findByLoginId(anyString());
    }

    // ---------- 02 서비스 관리자 로그인 ----------

    @Test
    @DisplayName("02 서비스 관리자 로그인 성공: admin.role = ADMIN, 토큰에 shopId 없음")
    void adminLogin() {
        when(adminRepository.findByLoginId("admin")).thenReturn(Optional.of(admin(1, "admin", "1234", "운영관리자")));

        AdminLoginResponse response = service.adminLogin(new LoginRequest("admin", "1234"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(43_200);
        assertThat(response.admin()).isEqualTo(new AdminLoginResponse.AdminInfo(1, "운영관리자", "ADMIN"));
        Jwt jwt = decoder.decode(response.accessToken());
        assertThat(jwt.getClaims()).doesNotContainKey(TokenClaims.SHOP_ID);
        assertThat(TokenClaims.principalOf(jwt)).contains(new LoginAdmin(1));
    }

    @Test
    @DisplayName("02 비밀번호 틀림·없는 아이디 → LOGIN_FAILED")
    void adminLoginFailures() {
        when(adminRepository.findByLoginId("admin")).thenReturn(Optional.of(admin(1, "admin", "1234", "운영관리자")));
        when(adminRepository.findByLoginId("owner_a")).thenReturn(Optional.empty());

        assertLoginFailed(() -> service.adminLogin(new LoginRequest("admin", "wrong")));
        assertLoginFailed(() -> service.adminLogin(new LoginRequest("owner_a", "1234")));
        verify(staffRepository, never()).findByLoginId(anyString());
    }

    // ---------- 03 내 계정 ----------

    @Test
    @DisplayName("03 내 계정: 토큰의 staffId·shopId로만 찾는다 (격리 조건 포함)")
    void me() {
        when(staffRepository.findByIdAndShopId(1L, 1L))
            .thenReturn(Optional.of(staff(1, 1, "owner_a", "1234", "박사장", StaffRole.OWNER)));

        MeResponse me = service.me(new LoginStaff(1, 1, StaffRole.OWNER));

        assertThat(me).isEqualTo(new MeResponse(1, "박사장", StaffRole.OWNER, 1, "차곡카센터"));
        verify(staffRepository).findByIdAndShopId(1L, 1L);
        verify(staffRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("03 토큰은 유효한데 그 정비소에 그 계정이 없으면 401 AUTH_REQUIRED (다른 정비소 번호로는 못 찾는다)")
    void meNotFoundInThatShop() {
        when(staffRepository.findByIdAndShopId(1L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.me(new LoginStaff(1, 2, StaffRole.OWNER)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.AUTH_REQUIRED));
    }

    @Test
    @DisplayName("정비소가 없는 데이터 이상은 500 INTERNAL_ERROR (staff.shop_id는 FK라 정상 데이터에서는 일어나지 않는다)")
    void missingShopIsServerError() {
        when(staffRepository.findByIdAndShopId(1L, 9L))
            .thenReturn(Optional.of(staff(1, 9, "owner_a", "1234", "박사장", StaffRole.OWNER)));
        when(shopReader.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.me(new LoginStaff(1, 9, StaffRole.OWNER)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    private static BusinessException catchBusiness(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("BusinessException이 나야 한다");
    }
}
