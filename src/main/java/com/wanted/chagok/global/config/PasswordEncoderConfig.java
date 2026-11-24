package com.wanted.chagok.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호는 BCrypt 해시로만 저장·비교한다 (07 3.3 {@code staff.password_hash}, D-43·D-44). 평문은 저장하지도 로그에 남기지도 않는다.
 * URL 권한 규칙({@link SecurityConfig})과 섞이지 않게 별도 설정으로 둔다.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
