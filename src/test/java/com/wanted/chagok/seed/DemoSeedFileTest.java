package com.wanted.chagok.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시연 시드 파일 정적 검사 (DB 없음, {@code ./gradlew build}에서 돈다).
 *
 * <p>{@code db/seed/run-demo-seed.sh}가 실행 전에 거부하는 조건과 07 7장·{@code db/seed/README.md} 규칙을 파일만 보고 확인한다.
 * 시드를 실제로 넣어 보는 확인은 {@link DemoSeedTest}(CI db-test)가 한다.
 */
class DemoSeedFileTest {

    private static final Path SEED = Path.of("db", "seed", "demo_seed.sql");

    @Test
    @DisplayName("V1-11 정적: run-demo-seed.sh가 거부하는 문장(DELETE·TRUNCATE·DROP·ALTER·CREATE·UPDATE)이 없고 실행할 문장이 있다")
    void seedPassesRunnerGuards() throws IOException {
        List<String> lines = Files.readAllLines(SEED, StandardCharsets.UTF_8);

        // run-demo-seed.sh: grep -Ev '^\s*(--.*)?$' → 주석·빈 줄만 있으면 거부
        assertThat(lines).anyMatch(l -> !l.matches("^\\s*(--.*)?$"));
        // run-demo-seed.sh: grep -Eiq '^\s*(delete|truncate|drop|alter|create|update)\b'
        Pattern forbidden = Pattern.compile("^\\s*(delete|truncate|drop|alter|create|update)\\b", Pattern.CASE_INSENSITIVE);
        assertThat(lines).noneMatch(l -> forbidden.matcher(l).find());
    }

    @Test
    @DisplayName("V1-11 정적: 날짜는 CURDATE() 기준 상대값만, 비밀번호는 BCrypt 해시만 (평문·고정 날짜 없음)")
    void seedUsesRelativeDatesAndHashedPasswords() throws IOException {
        String sql = withoutComments(Files.readString(SEED, StandardCharsets.UTF_8));

        assertThat(sql).contains("CURDATE()");
        assertThat(Pattern.compile("'\\d{4}-\\d{2}-\\d{2}").matcher(sql).find())
            .as("고정 날짜 리터럴이 없어야 한다 (07 7장: 오늘 기준 상대값)").isFalse();
        assertThat(sql).doesNotContain("'1234'");
        assertThat(Pattern.compile("'\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}'").matcher(sql).find())
            .as("BCrypt 해시가 있어야 한다").isTrue();
    }

    private static String withoutComments(String sql) {
        return sql.replaceAll("(?m)--[^\\n]*", "");
    }
}
