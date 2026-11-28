package com.wanted.chagok.global.config;

import com.wanted.chagok.global.auth.JwtVerification;
import com.wanted.chagok.global.auth.SecurityErrorResponder;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * URL 권한 규칙은 이 파일 한 곳 (06 5장, CONVENTIONS 8장 "기본 거부").
 *
 * <ol>
 *   <li>{@code GET /actuator/health} — 공개</li>
 *   <li>{@code POST /api/auth/login}(01)·{@code POST /api/admin/auth/login}(02) — 공개. 이 두 경로·POST만이고,
 *       {@code /api/admin/**}보다 먼저 둔다(순서가 바뀌면 관리자 로그인이 403이 된다)</li>
 *   <li>{@code /api/admin/**} — 서비스 관리자(ADMIN)만. {@code /api/**}보다 먼저 둔다(순서가 바뀌면 관리자 API가 정비소 계정에 열린다)</li>
 *   <li>{@code /api/**} — 사장·정비사(OWNER·MECHANIC). 서비스 관리자 토큰은 403 (08 X9)</li>
 *   <li>그 밖 — 모두 거부. <b>새 API 경로는 여기 등록하지 않으면 닫혀 있다</b></li>
 * </ol>
 *
 * <p>로그인 API는 토큰이 필요 없으므로, 로그인 요청에 붙어 온 {@code Authorization} 헤더는 읽지 않는다. 읽으면 화면이 남겨 둔
 * 만료된 토큰 때문에 로그인 자체가 401로 막힌다. P1 사장 전용 경로는 그 API가 생기는 이슈(P1)에서 더한다.
 * 인증 실패 401·권한 부족 403의 본문은 {@link SecurityErrorResponder}.
 */
@Configuration
public class SecurityConfig {

    /** 토큰 없이 부를 수 있는 로그인 API (08 01·02). 이 목록과 메서드(POST)만 공개한다. */
    private static final String[] LOGIN_PATHS = {"/api/auth/login", "/api/admin/auth/login"};

    /** 로그인 요청의 {@code Authorization} 헤더는 무시하고, 그 밖의 요청은 기본 방식({@code Bearer})으로 읽는다. */
    private static BearerTokenResolver ignoreTokenOnLogin() {
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        RequestMatcher loginRequest = new OrRequestMatcher(Arrays.stream(LOGIN_PATHS)
            .map(path -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path))
            .toList());
        return request -> loginRequest.matches(request) ? null : defaultResolver.resolve(request);
    }

    /** ERROR 디스패치 + {@code /error}. 오류 페이지가 늘어나도 자동으로 열리지 않게 경로도 함께 본다. */
    private static RequestMatcher errorDispatchOfErrorPath() {
        return new AndRequestMatcher(
            new DispatcherTypeRequestMatcher(DispatcherType.ERROR),
            PathPatternRequestMatcher.withDefaults().matcher("/error"));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
        SecurityErrorResponder errorResponder) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 컨테이너가 오류를 /error로 넘기는 ERROR 디스패치만 연다(경로도 /error일 때만). 막으면 원래 상태 코드(400 등)가 401·403으로 바뀐다 (#17).
                // /error를 직접 부르는 요청(REQUEST 디스패치)은 아래 anyRequest().denyAll()이 그대로 막는다.
                .requestMatchers(errorDispatchOfErrorPath()).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, LOGIN_PATHS).permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").hasAnyRole("OWNER", "MECHANIC")
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth
                .bearerTokenResolver(ignoreTokenOnLogin())
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(JwtVerification.authenticationConverter()))
                .authenticationEntryPoint(errorResponder)
                .accessDeniedHandler(errorResponder))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(errorResponder)
                .accessDeniedHandler(errorResponder));
        return http.build();
    }

    /** 키는 {@code JWT_SECRET} 환경변수(application.yaml {@code chagok.jwt.secret}). 없거나 32바이트 미만이면 기동 실패. */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${chagok.jwt.secret}") String secret, Clock clock) {
        return JwtVerification.decoder(secret, clock);
    }

    @Bean
    public SecurityErrorResponder securityErrorResponder(JsonMapper jsonMapper) {
        return new SecurityErrorResponder(jsonMapper);
    }
}
