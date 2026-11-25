package com.wanted.chagok.global.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * 로그인 토큰 발급 (F-AUTH-01·02, 08 01·02). 검증은 {@link JwtVerification}이 하며, 클레임 이름·규약은 {@link TokenClaims}를 따른다.
 *
 * <ul>
 *   <li>사장·정비사 토큰: {@code sub} = staff.id, {@code role}, {@code shopId}, {@code exp}</li>
 *   <li>서비스 관리자 토큰: {@code sub} = platform_admin.id, {@code role} = {@code ADMIN}, <b>{@code shopId} 없음</b> (07 I-5)</li>
 *   <li>유효 시간 12시간(43,200초, M1 확정). 발급 시각은 주입한 {@link Clock} 기준이다</li>
 *   <li>서명: HS256, 키는 검증기와 같은 {@code JWT_SECRET}(32바이트 이상, 없으면 서버가 뜨지 않는다)</li>
 * </ul>
 *
 * <p>발급한 토큰은 서버에 저장하지 않는다. 로그아웃은 화면이 토큰을 지우는 것이고 서버 강제 무효화는 P2다(04 F-AUTH-01).
 * 토큰 문자열은 로그·예외 메시지에 남기지 않는다.
 */
@Component
public class JwtIssuer {

    /** 08 01 {@code expiresIn}과 {@link TokenClaims} 설명의 12시간. */
    public static final Duration TOKEN_TTL = Duration.ofHours(12);

    private final JwtEncoder encoder;
    private final Clock clock;

    public JwtIssuer(@Value("${chagok.jwt.secret}") String secret, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(JwtVerification.secretKey(secret)));
        this.clock = clock;
    }

    public IssuedToken issueForStaff(long staffId, long shopId, StaffRole role) {
        return issue(JwtClaimsSet.builder()
            .subject(String.valueOf(staffId))
            .claim(TokenClaims.ROLE, role.name())
            .claim(TokenClaims.SHOP_ID, shopId));
    }

    public IssuedToken issueForAdmin(long adminId) {
        return issue(JwtClaimsSet.builder()
            .subject(String.valueOf(adminId))
            .claim(TokenClaims.ROLE, TokenClaims.ADMIN_ROLE));
    }

    private IssuedToken issue(JwtClaimsSet.Builder claims) {
        Instant now = clock.instant();
        JwtClaimsSet claimsSet = claims.issuedAt(now).expiresAt(now.plus(TOKEN_TTL)).build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claimsSet)).getTokenValue();
        return new IssuedToken(token, TOKEN_TTL.toSeconds());
    }

    /** 발급 결과. {@code toString}에 토큰을 싣지 않는다(로그 노출 방지). */
    public record IssuedToken(String accessToken, long expiresInSeconds) {

        @Override
        public String toString() {
            return "IssuedToken[accessToken=***, expiresInSeconds=" + expiresInSeconds + "]";
        }
    }
}
