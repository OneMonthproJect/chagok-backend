package com.wanted.chagok.global.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "지금"의 기준은 이 {@link Clock} 하나 (07 1장, CONVENTIONS: {@code LocalDate.now()} 직접 호출 금지).
 *
 * <p>Asia/Seoul, 초 단위로 끊는다 — DB 시각은 {@code DATETIME}(소수 초 없음)이고 08 일시 형식에도 소수 초가 없다.
 * 테스트는 {@code Clock.fixed(…)}를 주입해 날짜 경계를 재현한다.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.tick(Clock.system(SEOUL), Duration.ofSeconds(1));
    }
}
