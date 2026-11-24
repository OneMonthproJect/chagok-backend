package com.wanted.chagok.global.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * 테스트 전용 토큰 만들기. 운영 코드에는 토큰 발급기가 없다(발급은 로그인 API, F-AUTH-01).
 * 클레임 이름은 {@link TokenClaims} 규약 그대로.
 */
public final class TestJwts {

    private TestJwts() {
    }

    /** 테스트마다 새로 만드는 임의 키 (저장소에 키를 두지 않는다). */
    public static String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static JWTClaimsSet.Builder staff(long staffId, long shopId, String role, Instant expiresAt) {
        return new JWTClaimsSet.Builder()
            .subject(String.valueOf(staffId))
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.SHOP_ID, shopId)
            .issueTime(Date.from(expiresAt.minusSeconds(43_200)))
            .expirationTime(Date.from(expiresAt));
    }

    public static JWTClaimsSet.Builder admin(long adminId, Instant expiresAt) {
        return new JWTClaimsSet.Builder()
            .subject(String.valueOf(adminId))
            .claim(TokenClaims.ROLE, TokenClaims.ADMIN_ROLE)
            .issueTime(Date.from(expiresAt.minusSeconds(43_200)))
            .expirationTime(Date.from(expiresAt));
    }

    public static String sign(String secret, JWTClaimsSet.Builder claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
