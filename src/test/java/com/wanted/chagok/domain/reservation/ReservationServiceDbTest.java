package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.domain.reservation.api.ReservationRef;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationListResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * reservation을 실제 표(V1)에서 확인한다: 기간 조회·등록·변경·방문 완료의 SQL, {@code version} 증가(R11), 목적 행 교체,
 * 정비소 격리(X4·X7·X11), 유효 예약(R3). 앱 전체가 뜨고 customer·item의 <b>실제 구현</b>과 연결되므로 MySQL이 필요하다
 * ({@code ./gradlew dbTest}, CI db-test). 정비소·계정·고객·차량·항목은 이 테스트가 직접 넣고 테스트마다 롤백한다.
 *
 * <p>요청 사이를 흉내 내려고 단계 사이에 영속성 컨텍스트를 비운다({@code em.clear()}) — 안 그러면 같은 객체가 재사용되어
 * "DB에서 다시 읽은 예약"의 {@code version}을 확인하지 못한다.
 *
 * <p>LIMIT: 이 테스트는 한 트랜잭션 안에서 돌아 <b>서로 다른 트랜잭션의 동시 요청(R8·R10 둘째 경우)</b>은 확인하지 못한다.
 * 그건 커밋되는 데이터와 스레드 두 개가 필요해 별도로 다룬다(아직 없음, 미검증).
 */
@Tag("db")
@SpringBootTest
@Transactional
class ReservationServiceDbTest {

    private static final LocalDate TODAY = LocalDate.now(ClockConfig.SEOUL);

    @Autowired
    private ReservationService service;

    @Autowired
    private ReservationReader reader;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private long shopA;
    private long shopB;
    private long staffA;
    private long vehicleA1;
    private long vehicleA2;
    private long vehicleB;
    private long oil;
    private long filter;
    private long itemB;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소" + suffix);
        shopB = insertShop("B정비소" + suffix);
        staffA = insertStaff(shopA, "t_a_" + suffix);
        insertStaff(shopB, "t_b_" + suffix);
        long customerA = insertCustomer(shopA, "김민수", "010-0000-0001");
        long customerB = insertCustomer(shopB, "박지영", "010-0000-0009");
        vehicleA1 = insertVehicle(shopA, customerA, "12가0001");
        vehicleA2 = insertVehicle(shopA, customerA, "12가0002");
        vehicleB = insertVehicle(shopB, customerB, "99나0009");
        filter = insertItem(shopA, "AIR_FILTER", "에어필터", 2);
        oil = insertItem(shopA, "ENGINE_OIL", "엔진오일", 1);
        itemB = insertItem(shopB, "ENGINE_OIL", "엔진오일", 1);
    }

    // ------------------------------------------------------------------ 등록 (API 16)

    @Test
    @DisplayName("등록: 저장되고 version 0, 담당·등록자는 등록 계정, 목적 행이 함께 저장되고, 목록 조회로 같은 값이 돌아온다 (실제 customer·item 구현 연결)")
    void createThenList() {
        LocalDateTime at = TODAY.plusDays(2).atTime(11, 30);

        ReservationCreateResponse created = service.create(shopA, staffA, new ReservationCreateRequest(vehicleA1, at,
            new ReservationCreateRequest.Purpose(List.of(filter, oil), true, "엔진 소리")));
        em.flush();
        em.clear();

        assertThat(created.version()).isZero();
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT shop_id, vehicle_id, status, version, assignee_staff_id, created_by_staff_id, purpose_other, purpose_memo "
                + "FROM reservation WHERE id = ?", created.reservationId());
        assertThat(((Number) row.get("shop_id")).longValue()).isEqualTo(shopA);
        assertThat(row.get("status")).isEqualTo("RESERVED");
        assertThat(((Number) row.get("version")).intValue()).isZero();
        assertThat(((Number) row.get("assignee_staff_id")).longValue()).isEqualTo(staffA);
        assertThat(((Number) row.get("created_by_staff_id")).longValue()).isEqualTo(staffA);
        assertThat(row.get("purpose_memo")).isEqualTo("엔진 소리");
        assertThat(itemCount(created.reservationId())).isEqualTo(2);

        ReservationListResponse list = service.list(shopA, TODAY.plusDays(2), TODAY.plusDays(2));
        assertThat(list.items()).hasSize(1);
        ReservationListResponse.Item item = list.items().get(0);
        assertThat(item.reservationId()).isEqualTo(created.reservationId());
        assertThat(item.plateNumber()).isEqualTo("12가0001");
        assertThat(item.customerName()).isEqualTo("김민수");
        assertThat(item.phone()).isEqualTo("010-0000-0001");
        assertThat(item.purposeSummary()).isEqualTo("엔진오일·에어필터·기타"); // sortOrder 순 + 기타 맨 끝
        assertThat(item.needsAction()).isFalse();
    }

    @Test
    @DisplayName("X4 다른 정비소 차량으로 등록 → 404, X11 다른 정비소 항목 → 400, 아무것도 저장되지 않는다")
    void createIsolation() {
        LocalDateTime at = TODAY.plusDays(2).atTime(10, 0);

        assertThatThrownBy(() -> service.create(shopA, staffA, new ReservationCreateRequest(vehicleB, at, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.create(shopA, staffA, new ReservationCreateRequest(vehicleA1, at,
            new ReservationCreateRequest.Purpose(List.of(itemB), false, null))))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT));

        assertThat(reservationCount(shopA)).isZero();
        assertThat(reservationCount(shopB)).isZero();
    }

    @Test
    @DisplayName("R3 같은 차량에 유효 예약이 있으면 409 + 기존 예약 번호, R4 어제 이전의 '처리 필요' 예약은 막지 않는다")
    void activeReservationRule() {
        ReservationCreateResponse first = service.create(shopA, staffA,
            new ReservationCreateRequest(vehicleA1, TODAY.plusDays(3).atTime(10, 0), null));

        assertThatThrownBy(() -> service.create(shopA, staffA,
            new ReservationCreateRequest(vehicleA1, TODAY.plusDays(4).atTime(10, 0), null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.RESERVATION_ACTIVE_EXISTS);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", first.reservationId()));
            });

        insertReservation(shopA, vehicleA2, TODAY.minusDays(3).atTime(10, 0), "RESERVED", staffA); // 어제 이전 RESERVED
        ReservationCreateResponse second = service.create(shopA, staffA,
            new ReservationCreateRequest(vehicleA2, TODAY.plusDays(4).atTime(10, 0), null));
        assertThat(second.reservationId()).isPositive();
    }

    // ------------------------------------------------------------------ 기간 조회 (API 15)

    @Test
    @DisplayName("기간 조회 SQL: [from 0시, to+1일 0시) 경계 포함·제외, 시각 순, 취소 포함, 처리 필요, 다른 정비소 예약은 섞이지 않는다 (X7)")
    void listBoundariesOrderAndIsolation() {
        LocalDate from = TODAY.plusDays(10);
        LocalDate to = TODAY.plusDays(11);
        insertReservation(shopA, vehicleA1, from.minusDays(1).atTime(23, 30), "RESERVED", staffA); // 밖(전날 끝)
        long atStart = insertReservation(shopA, vehicleA1, from.atStartOfDay(), "RESERVED", staffA); // 안(경계)
        long canceled = insertReservation(shopA, vehicleA2, from.atTime(9, 0), "CANCELED", staffA); // 안(취소 포함)
        long atEnd = insertReservation(shopA, vehicleA1, to.atTime(23, 30), "RESERVED", staffA); // 안(마지막 시각)
        insertReservation(shopA, vehicleA1, to.plusDays(1).atStartOfDay(), "RESERVED", staffA); // 밖(다음 날 0시)
        insertReservation(shopB, vehicleB, from.atTime(8, 0), "RESERVED", insertStaff(shopB, "t_b2_" + suffix)); // 다른 정비소

        ReservationListResponse list = service.list(shopA, from, to);

        assertThat(list.items()).extracting(ReservationListResponse.Item::reservationId)
            .containsExactly(atStart, canceled, atEnd);
        assertThat(list.items().get(1).status()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("처리 필요: RESERVED이고 시각이 지난 예약만 true — 어제 예약은 true, 미래·취소는 false")
    void needsActionFromRealRows() {
        long overdue = insertReservation(shopA, vehicleA1, TODAY.minusDays(1).atTime(10, 0), "RESERVED", staffA);
        long future = insertReservation(shopA, vehicleA2, TODAY.plusDays(1).atTime(10, 0), "RESERVED", staffA);

        ReservationListResponse list = service.list(shopA, TODAY.minusDays(1), TODAY.plusDays(1));

        assertThat(list.items()).extracting(ReservationListResponse.Item::reservationId).containsExactly(overdue, future);
        assertThat(list.items().get(0).needsAction()).isTrue();
        assertThat(list.items().get(1).needsAction()).isFalse();
    }

    @Test
    @DisplayName("ReservationReader.findActiveByVehicleIds: 오늘 이후 RESERVED만, 차량마다 가장 이른 것, 다른 정비소 차량·취소·어제 이전은 빠진다")
    void findActiveByVehicleIds() {
        long early = insertReservation(shopA, vehicleA1, TODAY.plusDays(2).atTime(10, 0), "RESERVED", staffA);
        insertReservation(shopA, vehicleA1, TODAY.plusDays(5).atTime(10, 0), "RESERVED", staffA);
        insertReservation(shopA, vehicleA2, TODAY.plusDays(2).atTime(10, 0), "CANCELED", staffA);

        Map<Long, ReservationRef> active = reader.findActiveByVehicleIds(shopA, List.of(vehicleA1, vehicleA2, vehicleB));

        assertThat(active).containsOnlyKeys(vehicleA1);
        assertThat(active.get(vehicleA1).reservationId()).isEqualTo(early);
    }

    // ------------------------------------------------------------------ 변경 (API 19) — R11

    @Test
    @DisplayName("R11 예약 목적만 변경: 응답 version·DB version이 모두 1(정확히 한 번), 목적 행이 교체되고, 같은 version 0으로 다시 보내면 409")
    void purposeOnlyChangeBumpsVersionOnce() {
        long id = createReservation(vehicleA1, TODAY.plusDays(3).atTime(10, 0), List.of(oil));
        em.flush();
        em.clear();

        ReservationResponse response = service.update(shopA, id, update(0, null, List.of(filter), null, null));
        em.flush();
        em.clear();

        assertThat(response.version()).isEqualTo(1);
        assertThat(dbVersion(id)).isEqualTo(1);
        assertThat(itemIds(id)).containsExactly(filter);

        assertThatThrownBy(() -> service.update(shopA, id, update(0, null, List.of(oil), null, null)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
        em.clear();
        assertThat(itemIds(id)).containsExactly(filter); // 실패한 요청은 목적 행을 바꾸지 않았다
        assertThat(dbVersion(id)).isEqualTo(1);

        ReservationResponse second = service.update(shopA, id, update(1, null, List.of(oil), null, null));
        assertThat(second.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("R11 일시와 목적을 함께 바꿔도 version은 +1 한 번만 (+2가 아니다), 일시가 바뀌고 응답에 담긴다")
    void timeAndPurposeChangeBumpsVersionOnce() {
        long id = createReservation(vehicleA1, TODAY.plusDays(3).atTime(10, 0), List.of(oil));
        em.flush();
        em.clear();
        LocalDateTime newAt = TODAY.plusDays(6).atTime(9, 0);

        ReservationResponse response = service.update(shopA, id, update(0, newAt, List.of(filter), true, "메모"));
        em.flush();
        em.clear();

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.reservedAt()).isEqualTo(newAt);
        assertThat(dbVersion(id)).isEqualTo(1);
        // 시각은 앱과 같은 방식(Hibernate, jdbc.time_zone)으로 읽는다. JdbcTemplate은 JVM 시간대로 해석해 9시간 어긋날 수 있다
        Reservation reloaded = reservations.findById(id).orElseThrow();
        assertThat(reloaded.getReservedAt()).isEqualTo(newAt);
        assertThat(reloaded.getPurposeMemo()).isEqualTo("메모");
        assertThat(reloaded.isPurposeOther()).isTrue();
    }

    @Test
    @DisplayName("메모만 바꿔도 version +1 한 번, 메모를 빈 문자열로 보내면 비워진다")
    void memoOnlyChange() {
        long id = createReservation(vehicleA1, TODAY.plusDays(3).atTime(10, 0), List.of());
        service.update(shopA, id, update(0, null, null, null, "첫 메모"));
        em.flush();
        em.clear();
        assertThat(dbVersion(id)).isEqualTo(1);

        service.update(shopA, id, update(1, null, null, null, ""));
        em.flush();
        em.clear();

        assertThat(dbVersion(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT purpose_memo FROM reservation WHERE id = ?", String.class, id)).isNull();
    }

    @Test
    @DisplayName("목적 행 교체: 같은 항목을 다시 보내도 유일 제약(예약+항목)과 부딪히지 않고, 빈 목록이면 모두 지워진다")
    void replaceItemsWithSameItemAndClear() {
        long id = createReservation(vehicleA1, TODAY.plusDays(3).atTime(10, 0), List.of(oil, filter));
        em.flush();
        em.clear();

        service.update(shopA, id, update(0, null, List.of(oil), null, null));
        em.flush();
        em.clear();
        assertThat(itemIds(id)).containsExactly(oil);

        service.update(shopA, id, update(1, null, List.of(), null, null));
        em.flush();
        em.clear();
        assertThat(itemIds(id)).isEmpty();
        assertThat(dbVersion(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("변경 실패는 아무것도 바꾸지 않는다: 다른 정비소 항목(400)이면 기존 목적 행과 version이 그대로")
    void failedChangeLeavesRowsUntouched() {
        long id = createReservation(vehicleA1, TODAY.plusDays(3).atTime(10, 0), List.of(oil));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.update(shopA, id, update(0, null, List.of(itemB), null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT));
        em.clear();

        assertThat(itemIds(id)).containsExactly(oil);
        assertThat(dbVersion(id)).isZero();
    }

    @Test
    @DisplayName("X 격리: 다른 정비소 토큰으로 변경·방문 완료 → 404, 데이터는 그대로 / 완료된 예약 변경(R9)은 409")
    void updateAndCompleteIsolationAndStatus() {
        long id = createReservation(vehicleA1, TODAY.plusDays(3).atTime(10, 0), List.of(oil));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.update(shopB, id, update(0, TODAY.plusDays(5).atTime(9, 0), null, null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.complete(shopB, id, 0))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        em.clear();
        assertThat(dbVersion(id)).isZero();

        ReservationResponse done = service.complete(shopA, id, 0);
        em.flush();
        em.clear();
        assertThat(done.status()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(done.version()).isEqualTo(1);
        assertThat(dbVersion(id)).isEqualTo(1);

        assertThatThrownBy(() -> service.update(shopA, id, update(1, TODAY.plusDays(5).atTime(9, 0), null, null, null)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        assertThatThrownBy(() -> service.complete(shopA, id, 1))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    }

    @Test
    @DisplayName("변경: 같은 차량에 다른 유효 예약이 있으면 409(자기 자신은 제외), 어제 날짜(R7)는 400")
    void updateActiveConflictAndPastDate() {
        long id = createReservation(vehicleA1, TODAY.plusDays(3).atTime(10, 0), List.of());
        long other = insertReservation(shopA, vehicleA1, TODAY.plusDays(8).atTime(10, 0), "RESERVED", staffA);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.update(shopA, id, update(0, TODAY.plusDays(4).atTime(9, 0), null, null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.RESERVATION_ACTIVE_EXISTS);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", other));
            });
        assertThatThrownBy(() -> service.update(shopA, id, update(0, TODAY.minusDays(1).atTime(9, 0), null, null, null)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.RESERVATION_PAST_DATE));
        em.clear();
        assertThat(dbVersion(id)).isZero();
    }

    @Test
    @DisplayName("변경: 지난 날짜 RESERVED 예약은 같은 차량에 미래 유효 예약이 있어도 메모만은 바뀌고(version +1), 일시를 오늘 이후로 옮기면 409")
    void pastReservationMemoOnlyAndMoveToFuture() {
        long past = insertReservation(shopA, vehicleA1, TODAY.minusDays(2).atTime(10, 0), "RESERVED", staffA);
        long future = insertReservation(shopA, vehicleA1, TODAY.plusDays(6).atTime(10, 0), "RESERVED", staffA);
        em.flush();
        em.clear();

        service.update(shopA, past, update(0, null, null, null, "정정 메모"));
        em.flush();
        em.clear();
        assertThat(dbVersion(past)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT purpose_memo FROM reservation WHERE id = ?", String.class, past))
            .isEqualTo("정정 메모");
        assertThat(reservations.findById(past).orElseThrow().getReservedAt()).isEqualTo(TODAY.minusDays(2).atTime(10, 0));

        assertThatThrownBy(() -> service.update(shopA, past, update(1, TODAY.plusDays(2).atTime(9, 0), null, null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.RESERVATION_ACTIVE_EXISTS);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", future));
            });
        em.clear();
        assertThat(dbVersion(past)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private long createReservation(long vehicleId, LocalDateTime at, List<Long> itemIds) {
        return service.create(shopA, staffA, new ReservationCreateRequest(vehicleId, at,
            new ReservationCreateRequest.Purpose(itemIds, false, null))).reservationId();
    }

    private static ReservationUpdateRequest update(int version, LocalDateTime at, List<Long> itemIds, Boolean other,
                                                   String memo) {
        boolean hasPurpose = itemIds != null || other != null || memo != null;
        return new ReservationUpdateRequest(version, at,
            hasPurpose ? new ReservationCreateRequest.Purpose(itemIds, other, memo) : null);
    }

    private int dbVersion(long reservationId) {
        return jdbc.queryForObject("SELECT version FROM reservation WHERE id = ?", Integer.class, reservationId);
    }

    private int itemCount(long reservationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation_item WHERE reservation_id = ?", Integer.class, reservationId);
    }

    private List<Long> itemIds(long reservationId) {
        return jdbc.queryForList("SELECT service_item_id FROM reservation_item WHERE reservation_id = ? ORDER BY service_item_id",
            Long.class, reservationId);
    }

    private int reservationCount(long shopId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE shop_id = ?", Integer.class, shopId);
    }

    private long lastInsertId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertShop(String name) {
        jdbc.update("INSERT INTO shop (name, phone, created_at) VALUES (?, ?, ?)", name, "02-000-0000", LocalDateTime.now());
        return lastInsertId();
    }

    private long insertStaff(long shopId, String loginId) {
        jdbc.update("INSERT INTO staff (shop_id, login_id, password_hash, name, role, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            shopId, loginId, "x", "직원", "OWNER", LocalDateTime.now());
        return lastInsertId();
    }

    private long insertCustomer(long shopId, String name, String phone) {
        jdbc.update("INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            shopId, name, phone, true, LocalDateTime.now(), LocalDateTime.now());
        return lastInsertId();
    }

    private long insertVehicle(long shopId, long customerId, String plate) {
        jdbc.update("INSERT INTO vehicle (shop_id, customer_id, plate_number, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            shopId, customerId, plate, LocalDateTime.now(), LocalDateTime.now());
        return lastInsertId();
    }

    private long insertItem(long shopId, String code, String name, int sortOrder) {
        jdbc.update("INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", shopId, code, name, 6, 10_000, true, sortOrder, LocalDateTime.now());
        return lastInsertId();
    }

    /**
     * 시각이 들어가는 예약 행은 앱과 같은 방식(Hibernate)으로 저장한다. JdbcTemplate으로 {@code LocalDateTime}을 넣으면 JVM
     * 시간대(CI는 UTC)로 해석되어 앱이 읽는 값과 어긋난다. 상태만 바꾸는 UPDATE는 시각과 무관해 JdbcTemplate을 쓴다.
     */
    private long insertReservation(long shopId, long vehicleId, LocalDateTime at, String status, long staffId) {
        Reservation saved = reservations.saveAndFlush(Reservation.register(shopId, vehicleId, at, false, null, staffId));
        if (!"RESERVED".equals(status)) {
            jdbc.update("UPDATE reservation SET status = ? WHERE id = ?", status, saved.getId());
            em.clear();
        }
        return saved.getId();
    }
}
