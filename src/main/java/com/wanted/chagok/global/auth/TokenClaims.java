package com.wanted.chagok.global.auth;

import java.util.Optional;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 차곡 JWT의 클레임 규약 (내부 규약 — 화면은 토큰을 열어 보지 않으므로 08 범위 밖). 발급(로그인 API 01·02, F-AUTH-01)도 이 이름을 쓴다.
 *
 * <table>
 *   <tr><th>클레임</th><th>사장·정비사 토큰</th><th>서비스 관리자 토큰</th></tr>
 *   <tr><td>{@code sub}</td><td>staff.id (양의 정수 문자열)</td><td>platform_admin.id</td></tr>
 *   <tr><td>{@code role}</td><td>{@code OWNER} · {@code MECHANIC}</td><td>{@code ADMIN}</td></tr>
 *   <tr><td>{@code shopId}</td><td>소속 정비소 id (양의 정수, 필수)</td><td><b>없어야 한다</b> (07 I-5)</td></tr>
 *   <tr><td>{@code exp}</td><td colspan="2">필수. 발급 후 12시간(43200초, M1 확정)</td></tr>
 * </table>
 *
 * <p>이 규약에 맞지 않는 토큰은 서명이 맞아도 401이다(검증기 {@link JwtVerification}). 검증기와 권한 변환기가 같은 규칙을 쓰도록
 * 해석은 {@link #principalOf(Jwt)} 한 곳에서만 한다.
 */
public final class TokenClaims {

    public static final String ROLE = "role";
    public static final String SHOP_ID = "shopId";
    public static final String ADMIN_ROLE = "ADMIN";

    private TokenClaims() {
    }

    /** 토큰 → {@link LoginStaff} 또는 {@link LoginAdmin}. 규약에 맞지 않으면 빈 값. */
    public static Optional<Object> principalOf(Jwt jwt) {
        Long subject = positiveLong(jwt.getSubject());
        Object role = jwt.getClaims().get(ROLE);
        if (subject == null || !(role instanceof String roleName)) {
            return Optional.empty();
        }
        boolean hasShopId = jwt.getClaims().containsKey(SHOP_ID);
        if (ADMIN_ROLE.equals(roleName)) {
            return hasShopId ? Optional.empty() : Optional.of(new LoginAdmin(subject));
        }
        StaffRole staffRole = staffRole(roleName);
        Long shopId = hasShopId ? positiveLong(jwt.getClaims().get(SHOP_ID)) : null;
        if (staffRole == null || shopId == null) {
            return Optional.empty();
        }
        return Optional.of(new LoginStaff(subject, shopId, staffRole));
    }

    private static StaffRole staffRole(String name) {
        for (StaffRole r : StaffRole.values()) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    private static Long positiveLong(Object value) {
        long v;
        if (value instanceof Number n && (n instanceof Long || n instanceof Integer)) {
            v = n.longValue();
        } else if (value instanceof String s && !s.isEmpty() && s.chars().allMatch(Character::isDigit) && s.length() <= 18) {
            v = Long.parseLong(s);
        } else {
            return null;
        }
        return v > 0 ? v : null;
    }
}
