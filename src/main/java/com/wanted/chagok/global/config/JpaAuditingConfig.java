package com.wanted.chagok.global.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * {@code created_at}·{@code updated_at}은 DB 기본값이 없고 서버가 넣는다 (07 1장, V1-13). 그 시각은 {@link ClockConfig}의 Clock.
 *
 * <p>Entity는 {@code @EntityListeners(AuditingEntityListener.class)} + {@code @CreatedDate}(updatable = false)·
 * {@code @LastModifiedDate}, 타입 {@code LocalDateTime} (07 6.2 JPA 매핑 표). 별도 설정 클래스로 둔 것은
 * {@code @WebMvcTest} 같은 슬라이스 테스트가 JPA 없이 뜨게 하려는 것이다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
