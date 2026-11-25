package com.wanted.chagok.global;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재발 방지: SQL로 직접 넣은 {@code DATETIME}(시연 시드 {@code demo_seed.sql}가 이렇게 넣는다)을 앱(Hibernate)이 <b>같은 값으로</b> 읽는지,
 * 그리고 JVM 기본 시간대가 {@code Asia/Seoul}인지 본다 (chagok-backend#65).
 *
 * <p>앱은 {@code Clock}·{@code jdbc.time_zone}·JDBC URL의 {@code serverTimezone}을 모두 {@code Asia/Seoul}로 가정한다. 그런데 JVM 기본 시간대가
 * 환경에 맡겨져 있으면 UTC 서버(CI, Docker 등)에서 SQL로 넣은 시각이 정확히 9시간 어긋나 읽혔다
 * (시드에 10:00으로 넣은 예약이 {@code 01:00}으로). 한국 시간 PC에서는 드러나지 않으므로, 이 테스트는 JVM을 UTC로 띄운
 * 환경({@code JAVA_TOOL_OPTIONS=-Duser.timezone=UTC})에서 의미가 있다. 한국 시간 환경에서는 수정 없이도 통과한다.
 * 앱 시간대를 고정하는 코드가 없으면 UTC JVM에서 실패한다.
 *
 * <p>MySQL이 필요하다({@code ./gradlew dbTest}, CI db-test). 정비소·차량 등은 이 테스트가 직접 넣고 롤백한다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class JvmTimeZoneDbTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationRepository reservations;

    @Test
    @DisplayName("JVM 기본 시간대는 Asia/Seoul이다 (앱이 시작할 때 고정, 환경에 맡기지 않는다)")
    void jvmDefaultTimeZoneIsSeoul() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("SQL 문자열로 넣은 DATETIME 10:00을 Hibernate가 10:00으로 읽는다 (9시간 어긋나지 않는다)")
    void sqlLiteralDateTimeIsReadAsSameWallClock() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO shop (name, phone, created_at) VALUES (?, ?, '2026-11-25 09:00:00')", "시간대" + suffix, "02-000-0000");
        long shop = lastInsertId();
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) "
            + "VALUES (?, ?, 'x', '직원', 'OWNER', '2026-11-25 09:00:00')", shop, "t_tz_" + suffix);
        long staff = lastInsertId();
        jdbc.update("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) "
            + "VALUES (?, '고객', '010-0000-0000', TRUE, '2026-11-25 09:00:00', '2026-11-25 09:00:00')", shop);
        long customer = lastInsertId();
        jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, created_at, updated_at) "
            + "VALUES (?, ?, 'TZ0001', '2026-11-25 09:00:00', '2026-11-25 09:00:00')", shop, customer);
        long vehicle = lastInsertId();
        // 시드처럼 시각을 SQL 문자열로 직접 넣는다 (JDBC 바인딩을 거치지 않는다)
        jdbc.update("INSERT INTO reservation (shop_id, vehicle_id, reserved_at, status, purpose_other, assignee_staff_id, "
            + "created_by_staff_id, version, created_at, updated_at) "
            + "VALUES (?, ?, '2026-11-25 10:00:00', 'RESERVED', FALSE, ?, ?, 0, '2026-11-22 09:00:00', '2026-11-22 09:00:00')",
            shop, vehicle, staff, staff);
        long reservationId = lastInsertId();

        Reservation read = reservations.findById(reservationId).orElseThrow();

        assertThat(read.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 25, 10, 0));
    }

    private long lastInsertId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
