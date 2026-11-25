package com.wanted.chagok;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChagokApplication {

    /**
     * 앱의 시각은 모두 {@code Asia/Seoul} 기준이다: {@code Clock}(ClockConfig), {@code jdbc.time_zone}(application.yaml),
     * JDBC URL의 {@code serverTimezone}. JVM 기본 시간대만 실행 환경에 맡기면 UTC 서버(CI, Docker 등)에서 SQL로 직접 넣은
     * {@code DATETIME}(시연 시드)이 정확히 9시간 어긋나 읽힌다 (chagok-backend#65). 그래서 클래스가 로드될 때 한 번 고정한다.
     * {@code main}이 아니라 {@code static} 블록인 것은 테스트({@code @SpringBootTest})처럼 {@code main}을 거치지 않고
     * 시작해도 적용되게 하려는 것이다. 근거: 07 1장(시각은 Asia/Seoul), 08 0.1(일시 형식).
     */
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(ChagokApplication.class, args);
    }
}
