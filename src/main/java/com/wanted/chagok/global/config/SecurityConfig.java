package com.wanted.chagok.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 초기 설정: 헬스 체크만 열고 나머지는 모두 닫는다 (CONVENTIONS 8장 "기본 거부").
 * JWT 필터와 API별 규칙(/api/**, /api/admin/**)은 인증 `기반` 이슈에서 추가한다.
 * 새 API는 여기 등록하지 않으면 닫혀 있다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
