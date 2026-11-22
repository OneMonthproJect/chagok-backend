package com.wanted.chagok;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 앱이 뜨는지 확인한다: DB 연결 + Flyway 적용 + JPA validate.
 * DB 환경변수(.env 또는 OS 환경변수)가 있어야 돈다: `./gradlew dbTest`. CI는 backend-ci의 db-test 작업(MySQL)에서 돈다.
 */
@Tag("db")
@SpringBootTest
class ChagokApplicationTests {

    @Test
    void contextLoads() {
    }
}
