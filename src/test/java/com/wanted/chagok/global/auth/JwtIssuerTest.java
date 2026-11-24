package com.wanted.chagok.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.global.auth.JwtIssuer.IssuedToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 토큰 발급(F-AUTH-01·02)이 검증기({@link JwtVerification})와 같은 규약({@link TokenClaims})을 지키는지 본다.
 * 발급한 토큰을 실제 검증기로 다시 읽어 확인한다. DB·Spring 없이 돈다.
 */
class JwtIssuerTest {

    private static final Instant NOW = Instant.parse("2026-11-24T01:00:00Z");
    private static final long TWELVE_HOURS = 43_200;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final String secret = TestJwts.randomSecret();
    private final JwtIssuer issuer = new JwtIssuer(secret, clock);
    private final JwtDecoder decoder = JwtVerification.decoder(secret, clock);

    @Test
    @DisplayName("사장 토큰: sub·role·shopId·exp 규약, 12시간(43200초), 검증기가 LoginStaff로 읽는다")
    void staffToken() {
        IssuedToken token = issuer.issueForStaff(7, 3, StaffRole.OWNER);

        Jwt jwt = decoder.decode(token.accessToken());
        assertThat(token.expiresInSeconds()).isEqualTo(TWELVE_HOURS);
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaims()).containsEntry(TokenClaims.ROLE, "OWNER");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(TWELVE_HOURS));
        assertThat(TokenClaims.principalOf(jwt)).contains(new LoginStaff(7, 3, StaffRole.OWNER));
    }

    @Test
    @DisplayName("정비사 토큰도 같은 규약")
    void mechanicToken() {
        Jwt jwt = decoder.decode(issuer.issueForStaff(2, 1, StaffRole.MECHANIC).accessToken());

        assertThat(TokenClaims.principalOf(jwt)).contains(new LoginStaff(2, 1, StaffRole.MECHANIC));
    }

    @Test
    @DisplayName("서비스 관리자 토큰: role = ADMIN, shopId 클레임이 없다 (07 I-5), 검증기가 LoginAdmin으로 읽는다")
    void adminToken() {
        IssuedToken token = issuer.issueForAdmin(9);

        Jwt jwt = decoder.decode(token.accessToken());
        assertThat(jwt.getClaims()).containsEntry(TokenClaims.ROLE, TokenClaims.ADMIN_ROLE);
        assertThat(jwt.getClaims()).doesNotContainKey(TokenClaims.SHOP_ID);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(TWELVE_HOURS));
        assertThat(TokenClaims.principalOf(jwt)).contains(new LoginAdmin(9));
    }

    @Test
    @DisplayName("만료: 발급 12시간 뒤 1초가 지나면 검증기가 거부, 1초 전까지는 통과")
    void expiresAfterTwelveHours() {
        String token = issuer.issueForStaff(1, 1, StaffRole.OWNER).accessToken();

        JwtDecoder justBefore = JwtVerification.decoder(secret, Clock.fixed(NOW.plusSeconds(TWELVE_HOURS - 1), ZoneOffset.UTC));
        JwtDecoder justAfter = JwtVerification.decoder(secret, Clock.fixed(NOW.plusSeconds(TWELVE_HOURS + 1), ZoneOffset.UTC));
        assertThat(justBefore.decode(token).getSubject()).isEqualTo("1");
        assertThatThrownBy(() -> justAfter.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 검증기가 거부한다 (키는 환경변수 JWT_SECRET 하나)")
    void signedWithDifferentKeyIsRejected() {
        String token = new JwtIssuer(TestJwts.randomSecret(), clock).issueForStaff(1, 1, StaffRole.OWNER).accessToken();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("키가 비었거나 32바이트보다 짧으면 발급기를 만들 수 없다 (서버 기동 실패)")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtIssuer("short", clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtIssuer("", clock)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("발급 결과의 toString에는 토큰이 없다 (로그 노출 방지)")
    void issuedTokenToStringHidesToken() {
        IssuedToken token = issuer.issueForStaff(1, 1, StaffRole.OWNER);

        assertThat(token.toString()).doesNotContain(token.accessToken());
    }
}
