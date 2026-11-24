package com.wanted.chagok.global.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 일시는 {@code "2026-11-24T10:00:00"} — Asia/Seoul, 시간대 표기·소수 초 없음 (08 0.1, M1 확정 15번). {@code Z}·오프셋을 붙이지 않는다.
 * 날짜({@code LocalDate})는 Jackson 기본 {@code "2026-11-24"} 그대로.
 */
@Configuration
public class JacksonConfig {

    public static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    @Bean
    public JsonMapperBuilderCustomizer localDateTimeFormat() {
        return builder -> builder.addModule(new SimpleModule("chagok-date-time")
            .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(LOCAL_DATE_TIME)));
    }
}
