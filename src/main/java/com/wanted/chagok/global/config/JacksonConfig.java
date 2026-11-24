package com.wanted.chagok.global.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
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
        return builder -> builder
            .addModule(new SimpleModule("chagok-date-time")
                .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(LOCAL_DATE_TIME)))
            // 정수 자리에 소수(1.5)가 오면 잘라서 받지 않고 400으로 막는다 (08 0.1: 정수가 아닌 값은 400 INVALID_INPUT)
            .withCoercionConfig(LogicalType.Integer,
                cfg -> cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }
}
