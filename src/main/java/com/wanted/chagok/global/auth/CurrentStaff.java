package com.wanted.chagok.global.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 정비소 API Controller에서 로그인한 계정을 받는다: {@code list(@CurrentStaff LoginStaff staff, …)}.
 *
 * <p>{@code /api/**}는 SecurityConfig가 OWNER·MECHANIC 토큰만 통과시키므로 여기에는 항상 {@link LoginStaff}가 온다.
 * 다른 타입이면(설정 실수) 조용히 null을 넘기지 않고 오류를 낸다({@code errorOnInvalidType}).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal(errorOnInvalidType = true)
public @interface CurrentStaff {
}
