package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.AssigneeRef;
import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationListResponse;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationItem;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * API 15 기간 조회·API 16 예약 등록의 업무 규칙 (R1~R4, X4·X7·X8·X11) — customer·item 계약과 Repository는 가짜(DB 없음).
 * 실제 SQL(기간·정렬·유효 예약 필터)과 저장 후 {@code version = 0}, 실제 두 도메인 구현과의 연결은 DB·구현이 필요해
 * 여기서 증명하지 못한다(미검증). 정비소 값이 요청이 아니라 인자(토큰)에서 온다는 것과 호출 순서를 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationListCreateServiceTest {

    private static final long SHOP = 3L;
    private static final long STAFF = 7L;
    private static final long VEHICLE = 4L;

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    @Mock
    private ReservationRepository repository;

    @Mock
    private ReservationItemRepository itemRepository;

    @Mock
    private CustomerReader customerReader;

    @Mock
    private ItemReader itemReader;
    @Mock
    private StaffReader staffReader;

    private ReservationService service() {
        // 이 정비소의 담당 후보는 등록자(STAFF) 한 명. 목록 테스트는 쓰지 않으므로 lenient
        lenient().when(staffReader.findAssignees(SHOP)).thenReturn(List.of(new AssigneeRef(STAFF, "박사장", StaffRole.OWNER, 0)));
        return new ReservationService(repository, itemRepository, customerReader, itemReader, staffReader, clock);
    }

    private static ReservationCreateRequest request(LocalDateTime at, List<Long> itemIds, Boolean other, String memo) {
        return new ReservationCreateRequest(VEHICLE, at, new ReservationCreateRequest.Purpose(itemIds, other, memo), STAFF);
    }

    private static Reservation reservation(long id, long vehicleId, LocalDateTime at, ReservationStatus status,
                                           boolean other, int version) {
        Reservation r = Reservation.register(SHOP, vehicleId, at, other, null, STAFF);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", version);
        return r;
    }

    /** 저장하면 DB가 id·version을 채운 것처럼 돌려준다 (실제 채움은 JPA가 하며 dbTest에서 확인할 일). */
    private void saveAssignsIdAndVersion() {
        when(repository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 10L);
            ReflectionTestUtils.setField(r, "version", 0);
            return r;
        });
    }

    private static void assertInvalidInput(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(e.fieldErrors()).extracting("field").containsExactly(field);
        });
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    // ================= API 16 예약 등록 =================

    @Test
    @DisplayName("등록: 차량 소유 확인 → 항목 검증 → 저장. 담당·등록자는 등록한 계정(D-51), version은 0, 예약 목적 행 저장")
    void create() {
        saveAssignsIdAndVersion();
        when(repository.findByVehiclesAndStatusFrom(eq(SHOP), eq(List.of(VEHICLE)), eq(ReservationStatus.RESERVED), any()))
            .thenReturn(List.of());

        ReservationCreateResponse response = service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 27, 11, 30), List.of(1L, 4L), true, "엔진 소리"));

        assertThat(response.reservationId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.reservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 11, 30));
        assertThat(response.vehicleId()).isEqualTo(VEHICLE);
        assertThat(response.version()).isZero();

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getShopId()).isEqualTo(SHOP);
        assertThat(saved.getValue().getAssigneeStaffId()).isEqualTo(STAFF);
        assertThat(saved.getValue().getCreatedByStaffId()).isEqualTo(STAFF);
        assertThat(saved.getValue().isPurposeOther()).isTrue();
        assertThat(saved.getValue().getPurposeMemo()).isEqualTo("엔진 소리");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReservationItem>> items = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(items.capture());
        assertThat(items.getValue()).extracting(ReservationItem::getReservationId).containsOnly(10L);
        assertThat(items.getValue()).extracting(ReservationItem::getServiceItemId).containsExactly(1L, 4L);
        assertThat(items.getValue()).extracting(ReservationItem::getShopId).containsOnly(SHOP);
        verify(customerReader).getOwnedVehicle(SHOP, VEHICLE);
        verify(itemReader).requireActiveItems(SHOP, List.of(1L, 4L), "purpose.itemIds");
    }

    @Test
    @DisplayName("예약 목적이 없으면(purpose 생략) 항목 없음·기타 아님·메모 없음으로 저장, 항목 행은 저장하지 않는다")
    void createWithoutPurpose() {
        saveAssignsIdAndVersion();
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of());

        service().create(SHOP, STAFF, new ReservationCreateRequest(VEHICLE, LocalDateTime.of(2026, 11, 27, 11, 0), null, STAFF));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isPurposeOther()).isFalse();
        assertThat(saved.getValue().getPurposeMemo()).isNull();
        verify(itemRepository, never()).saveAll(any());
        verify(itemReader).requireActiveItems(SHOP, List.of(), "purpose.itemIds");
    }

    @Test
    @DisplayName("R1 10:15 → 400 INVALID_INPUT(reservedAt), 저장 없음. 소유 확인은 먼저 했다(08 16번 순서)")
    void notOnHalfHour() {
        assertInvalidInput(() -> service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 27, 10, 15), List.of(), false, null)), "reservedAt");

        verify(customerReader).getOwnedVehicle(SHOP, VEHICLE);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("R1 초가 00이 아님(10:30:15) → 400 INVALID_INPUT(reservedAt)")
    void secondsMustBeZero() {
        assertInvalidInput(() -> service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 27, 10, 30, 15), List.of(), false, null)), "reservedAt");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("R2 어제 날짜 → 400 RESERVATION_PAST_DATE, 저장 없음")
    void yesterdayIsRejected() {
        assertCode(() -> service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 23, 10, 0), List.of(), false, null)), ErrorCode.RESERVATION_PAST_DATE);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("R2 오늘 이미 지난 시각(지금 15:00, 09:00) → 성공 (D-23)")
    void todayPastTimeIsAllowed() {
        saveAssignsIdAndVersion();
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of());

        ReservationCreateResponse response = service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 24, 9, 0), List.of(), false, null));

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("R3 같은 차량에 유효 예약이 있으면 → 409 RESERVATION_ACTIVE_EXISTS + details.reservationId, 저장 없음")
    void activeReservationExists() {
        Reservation existing = reservation(3, VEHICLE, LocalDateTime.of(2026, 11, 26, 10, 0),
            ReservationStatus.RESERVED, false, 0);
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any()))
            .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 27, 10, 0), List.of(), false, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.RESERVATION_ACTIVE_EXISTS);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", 3L));
            });
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("R4 유효 예약 판정은 '오늘 0시 이후 RESERVED'만 본다 — 어제 이전 '처리 필요' 예약은 조회 대상이 아니라 새 예약이 성공")
    void overdueReservationDoesNotBlock() {
        saveAssignsIdAndVersion();
        when(repository.findByVehiclesAndStatusFrom(SHOP, List.of(VEHICLE), ReservationStatus.RESERVED,
            LocalDate.of(2026, 11, 24).atStartOfDay())).thenReturn(List.of());

        ReservationCreateResponse response = service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 27, 10, 0), List.of(), false, null));

        assertThat(response.reservationId()).isEqualTo(10L);
        verify(repository).findByVehiclesAndStatusFrom(SHOP, List.of(VEHICLE), ReservationStatus.RESERVED,
            LocalDate.of(2026, 11, 24).atStartOfDay());
    }

    @Test
    @DisplayName("X4 다른 정비소 차량 → customer 계약의 404 NOT_FOUND가 그대로 나가고, 항목 확인·저장은 하지 않는다")
    void otherShopsVehicle() {
        when(customerReader.getOwnedVehicle(SHOP, VEHICLE)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        assertCode(() -> service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 27, 10, 0), List.of(1L), false, null)), ErrorCode.NOT_FOUND);

        verifyNoInteractions(itemReader);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("X11 다른 정비소·쓰지 않는 항목 → item 계약의 400 INVALID_INPUT(purpose.itemIds)이 그대로 나가고 저장 없음")
    void invalidItems() {
        org.mockito.Mockito.doThrow(BusinessException.invalidField("purpose.itemIds", "사용할 수 없는 항목입니다."))
            .when(itemReader).requireActiveItems(SHOP, List.of(99L), "purpose.itemIds");

        assertInvalidInput(() -> service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 27, 10, 0), List.of(99L), false, null)), "purpose.itemIds");

        verify(repository, never()).save(any());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("같은 항목을 두 번 고르면 → 400 INVALID_INPUT(purpose.itemIds), 항목 검증·저장 없음")
    void duplicateItems() {
        assertInvalidInput(() -> service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 27, 10, 0), List.of(1L, 1L), false, null)), "purpose.itemIds");

        verifyNoInteractions(itemReader);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("itemIds에 null이 섞여 있으면 걸러 내지 않고 → 400 INVALID_INPUT(purpose.itemIds), 항목 검증·저장 없음")
    void nullItemId() {
        List<Long> withNull = new java.util.ArrayList<>();
        withNull.add(1L);
        withNull.add(null);

        assertInvalidInput(() -> service().create(SHOP, STAFF,
            request(LocalDateTime.of(2026, 11, 27, 10, 0), withNull, false, null)), "purpose.itemIds");

        verifyNoInteractions(itemReader);
        verify(repository, never()).save(any());
    }

    // ================= API 15 기간 조회 =================

    @Test
    @DisplayName("기간: 시작이 끝보다 늦으면 400 INVALID_INPUT(from), 42일 넘으면 400(to), 정확히 42일은 통과 — 조회 범위는 [from 0시, to+1일 0시)")
    void periodRules() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        assertInvalidInput(() -> service().list(SHOP, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 4)), "from");
        assertInvalidInput(() -> service().list(SHOP, from, from.plusDays(42)), "to"); // 43일
        verifyNoInteractions(repository);

        when(repository.findInPeriod(SHOP, from.atStartOfDay(), from.plusDays(42).atStartOfDay())).thenReturn(List.of());
        ReservationListResponse response = service().list(SHOP, from, from.plusDays(41)); // 42일

        assertThat(response.from()).isEqualTo(from);
        assertThat(response.to()).isEqualTo(from.plusDays(41));
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("예약이 없는 기간 → items [] 이고 customer·item은 부르지 않는다")
    void emptyPeriod() {
        when(repository.findInPeriod(anyLong(), any(), any())).thenReturn(List.of());

        assertThat(service().list(SHOP, LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 24)).items()).isEmpty();

        verifyNoInteractions(customerReader, itemReader, itemRepository);
    }

    @Test
    @DisplayName("목록: 시각 순 그대로, 취소 포함, 처리 필요(RESERVED+시각 지남), 목적 요약 '항목·기타', 차량·고객 요약을 한 번에 조회(X7)")
    void listMapsRows() {
        LocalDate day = LocalDate.of(2026, 11, 24);
        // 지금 15:00 — 10:00 예약은 시각이 지났다(처리 필요), 16:00은 아직, 취소는 처리 필요 아님
        Reservation overdue = reservation(1, 4L, day.atTime(10, 0), ReservationStatus.RESERVED, true, 0);
        Reservation later = reservation(2, 8L, day.atTime(16, 0), ReservationStatus.RESERVED, false, 2);
        Reservation canceled = reservation(3, 9L, day.atTime(17, 30), ReservationStatus.CANCELED, false, 1);
        when(repository.findInPeriod(SHOP, day.atStartOfDay(), day.plusDays(1).atStartOfDay()))
            .thenReturn(List.of(overdue, later, canceled));
        when(customerReader.findVehicleSummaries(eq(SHOP), anyCollection())).thenReturn(Map.of(
            4L, new VehicleSummary(4L, "12가0004", 40L, "오세훈", "010-0000-0004", true),
            8L, new VehicleSummary(8L, "12가0008", 80L, "김민수", "010-0000-0008", true),
            9L, new VehicleSummary(9L, "12가0009", 90L, "박지영", "010-0000-0009", true)));
        when(itemRepository.findByShopIdAndReservationIdIn(eq(SHOP), anyCollection())).thenReturn(List.of(
            ReservationItem.of(SHOP, 1L, 5L), ReservationItem.of(SHOP, 1L, 2L)));
        when(itemReader.findItems(eq(SHOP), anyCollection())).thenReturn(Map.of(
            5L, new ItemRef(5L, "BATTERY", "배터리", 36, 60000, true, 6),
            2L, new ItemRef(2L, "ENGINE_OIL", "엔진오일", 6, 10000, true, 1)));

        ReservationListResponse response = service().list(SHOP, day, day);

        assertThat(response.items()).extracting(ReservationListResponse.Item::reservationId).containsExactly(1L, 2L, 3L);
        ReservationListResponse.Item first = response.items().get(0);
        assertThat(first.reservedAt()).isEqualTo(day.atTime(10, 0));
        assertThat(first.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(first.needsAction()).isTrue();
        assertThat(first.version()).isZero();
        assertThat(first.vehicleId()).isEqualTo(4L);
        assertThat(first.plateNumber()).isEqualTo("12가0004");
        assertThat(first.customerId()).isEqualTo(40L);
        assertThat(first.customerName()).isEqualTo("오세훈");
        assertThat(first.phone()).isEqualTo("010-0000-0004");
        assertThat(first.purposeSummary()).isEqualTo("엔진오일·배터리·기타"); // sortOrder 순 + 기타는 맨 끝
        assertThat(response.items().get(1).needsAction()).isFalse();
        assertThat(response.items().get(1).purposeSummary()).isNull();
        assertThat(response.items().get(2).status()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(response.items().get(2).needsAction()).isFalse();

        verify(customerReader).findVehicleSummaries(SHOP, Set.of(4L, 8L, 9L)); // 정비소는 토큰 값, 차량은 한 번에
        verify(itemReader).findItems(SHOP, Set.of(5L, 2L));
    }

    @Test
    @DisplayName("예약의 차량 요약이 없으면(서버 버그) 조용히 넘기지 않고 예외 → 500")
    void missingVehicleSummary() {
        LocalDate day = LocalDate.of(2026, 11, 24);
        when(repository.findInPeriod(anyLong(), any(), any())).thenReturn(List.of(
            reservation(1, 4L, day.atTime(10, 0), ReservationStatus.RESERVED, false, 0)));
        when(customerReader.findVehicleSummaries(eq(SHOP), anyCollection())).thenReturn(Map.of());
        when(itemRepository.findByShopIdAndReservationIdIn(eq(SHOP), anyCollection())).thenReturn(List.of());
        when(itemReader.findItems(eq(SHOP), anyCollection())).thenReturn(Map.of());

        assertThatThrownBy(() -> service().list(SHOP, day, day)).isInstanceOf(IllegalStateException.class);
    }
}
