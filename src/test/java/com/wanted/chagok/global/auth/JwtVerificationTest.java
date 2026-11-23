package com.wanted.chagok.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * JWT 검증 (B-1): 서명·만료·클레임 규약. 실패는 모두 {@link JwtException} → 보안 필터에서 401 {@code AUTH_REQUIRED}
 * (EndpointGuardTest에서 실제 응답 확인). DB 없이 돈다.
 */
class JwtVerificationTest {

    private static final Instant NOW = Instant.parse("2026-11-24T01:00:00Z"); // 서울 10:00
    private static final Clock CLOCK = Clock.fixed(NOW, ClockConfig.SEOUL);
    private static final Instant LATER = NOW.plusSeconds(3_600);

    private final String secret = TestJwts.randomSecret();
    private final JwtDecoder decoder = JwtVerification.decoder(secret, CLOCK);

    @Test
    @DisplayName("① 정상 사장·정비사 토큰 → LoginStaff(staffId, shopId, role), 권한 ROLE_OWNER·ROLE_MECHANIC")
    void validStaffToken() {
        var owner = JwtVerification.authenticationConverter()
            .convert(decoder.decode(TestJwts.sign(secret, TestJwts.staff(1, 7, "OWNER", LATER))));
        assertThat(owner.getPrincipal()).isEqualTo(new LoginStaff(1, 7, StaffRole.OWNER));
        assertThat(owner.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_OWNER");

        var mechanic = JwtVerification.authenticationConverter()
            .convert(decoder.decode(TestJwts.sign(secret, TestJwts.staff(2, 7, "MECHANIC", LATER))));
        assertThat(mechanic.getPrincipal()).isEqualTo(new LoginStaff(2, 7, StaffRole.MECHANIC));
        assertThat(mechanic.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_MECHANIC");
    }

    @Test
    @DisplayName("② 정상 서비스 관리자 토큰(shopId 없음) → LoginAdmin, 권한 ROLE_ADMIN")
    void validAdminToken() {
        var admin = JwtVerification.authenticationConverter()
            .convert(decoder.decode(TestJwts.sign(secret, TestJwts.admin(1, LATER))));
        assertThat(admin.getPrincipal()).isEqualTo(new LoginAdmin(1));
        assertThat(admin.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("③ 만료된 토큰 → 거부 (Clock 기준, 허용 오차 0초: 만료 1초 뒤도 거부)")
    void expiredToken() {
        String expired = TestJwts.sign(secret, TestJwts.staff(1, 7, "OWNER", NOW.minusSeconds(1)));
        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("④ 다른 키로 서명한 토큰 → 거부")
    void wrongSignature() {
        String forged = TestJwts.sign(TestJwts.randomSecret(), TestJwts.staff(1, 7, "OWNER", LATER));
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("⑤ 형식이 잘못된 토큰·서명 없는(alg none) 토큰 → 거부")
    void malformedToken() {
        assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("a.b.c")).isInstanceOf(JwtException.class);
        String valid = TestJwts.sign(secret, TestJwts.staff(1, 7, "OWNER", LATER));
        String payload = valid.split("\\.")[1];
        String unsigned = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes()) + "." + payload + ".";
        assertThatThrownBy(() -> decoder.decode(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("⑥ exp가 없는 토큰 → 거부 (만료 없는 토큰을 받지 않는다)")
    void missingExpiry() {
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
            .subject("1").claim(TokenClaims.ROLE, "OWNER").claim(TokenClaims.SHOP_ID, 7L);
        assertThatThrownBy(() -> decoder.decode(TestJwts.sign(secret, claims))).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("⑦ shopId가 없거나 0 이하인 사장·정비사 토큰 → 거부")
    void staffWithoutShopId() {
        var noShop = TestJwts.staff(1, 7, "OWNER", LATER).claim(TokenClaims.SHOP_ID, null);
        assertThatThrownBy(() -> decoder.decode(TestJwts.sign(secret, noShop))).isInstanceOf(JwtException.class);
        var zeroShop = TestJwts.staff(1, 0, "MECHANIC", LATER);
        assertThatThrownBy(() -> decoder.decode(TestJwts.sign(secret, zeroShop))).isInstanceOf(JwtException.class);
        var textShop = TestJwts.staff(1, 7, "OWNER", LATER).claim(TokenClaims.SHOP_ID, "7; drop");
        assertThatThrownBy(() -> decoder.decode(TestJwts.sign(secret, textShop))).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("⑧ shopId가 있는 서비스 관리자 토큰 → 거부 (관리자 토큰에는 shop_id가 없다, 07 I-5)")
    void adminWithShopId() {
        var adminWithShop = TestJwts.admin(1, LATER).claim(TokenClaims.SHOP_ID, 7L);
        assertThatThrownBy(() -> decoder.decode(TestJwts.sign(secret, adminWithShop))).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("⑨ 모르는 role·role 없음·숫자가 아닌 sub → 거부")
    void unknownRoleOrSubject() {
        var unknownRole = TestJwts.staff(1, 7, "SUPERUSER", LATER);
        assertThatThrownBy(() -> decoder.decode(TestJwts.sign(secret, unknownRole))).isInstanceOf(JwtException.class);
        var noRole = TestJwts.staff(1, 7, "OWNER", LATER).claim(TokenClaims.ROLE, null);
        assertThatThrownBy(() -> decoder.decode(TestJwts.sign(secret, noRole))).isInstanceOf(JwtException.class);
        var badSubject = TestJwts.staff(1, 7, "OWNER", LATER).subject("owner_a");
        assertThatThrownBy(() -> decoder.decode(TestJwts.sign(secret, badSubject))).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("키가 비었거나 32바이트보다 짧으면 디코더를 만들지 않는다 (기동 실패)")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> JwtVerification.decoder("", CLOCK)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtVerification.decoder(null, CLOCK)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtVerification.decoder("x".repeat(31), CLOCK)).isInstanceOf(IllegalStateException.class);
        Jwt ok = JwtVerification.decoder("x".repeat(32), CLOCK)
            .decode(TestJwts.sign("x".repeat(32), TestJwts.admin(1, LATER)));
        assertThat(ok.getSubject()).isEqualTo("1");
    }
}
