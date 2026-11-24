package com.wanted.chagok.global.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * JWT 검증 (B-1). 서명(HS256)·만료·클레임 규약({@link TokenClaims})을 본다. 하나라도 어긋나면 401 {@code AUTH_REQUIRED}.
 *
 * <ul>
 *   <li>키: {@code JWT_SECRET} 환경변수, 32바이트(256비트) 이상. 짧으면 서버가 뜨지 않는다. 저장소에 키를 두지 않는다</li>
 *   <li>{@code exp} 필수. 만료 판정은 주입한 {@link Clock} 기준이고 허용 오차는 0초</li>
 *   <li>알고리즘은 HS256만 받는다({@code alg: none}·다른 알고리즘은 거부)</li>
 * </ul>
 *
 * <p><b>하지 않는 것 (잔여 제한사항)</b>: 토큰을 받은 뒤 바뀐 계정 상태(정비사 비활성화·정비소 정지·역할·소속 변경)는 확인하지 않는다.
 * 그런 토큰도 만료(최대 12시간)까지 유효하다. 정책은 chagok-docs#64에서 정한다. 토큰 발급은 로그인 API(F-AUTH-01)에서 만든다.
 */
public final class JwtVerification {

    public static final int MIN_SECRET_BYTES = 32;

    private JwtVerification() {
    }

    public static JwtDecoder decoder(String secret, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(secret))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
            requireExpiry(), timestamps, requireClaimContract())));
        return decoder;
    }

    /** 검증을 통과한 토큰 → 인증 객체. principal은 {@link LoginStaff} 또는 {@link LoginAdmin}, 권한은 {@code ROLE_역할}. */
    public static Converter<Jwt, AbstractAuthenticationToken> authenticationConverter() {
        return jwt -> {
            Object principal = TokenClaims.principalOf(jwt)
                .orElseThrow(() -> new InvalidBearerTokenException("token claims do not match the contract"));
            String role = principal instanceof LoginStaff staff ? staff.role().name() : TokenClaims.ADMIN_ROLE;
            return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        };
    }

    static SecretKey secretKey(String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT_SECRET이 비어 있거나 " + MIN_SECRET_BYTES + "바이트보다 짧습니다. README 「환경변수」를 보고 설정하세요.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private static OAuth2TokenValidator<Jwt> requireExpiry() {
        return jwt -> jwt.getExpiresAt() != null
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(invalid("exp claim is required"));
    }

    private static OAuth2TokenValidator<Jwt> requireClaimContract() {
        return jwt -> TokenClaims.principalOf(jwt).isPresent()
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(invalid("token claims do not match the contract"));
    }

    private static OAuth2Error invalid(String description) {
        return new OAuth2Error("invalid_token", description, null);
    }
}
