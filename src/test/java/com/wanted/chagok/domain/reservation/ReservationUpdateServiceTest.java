package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationItem;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.service.ReservationService;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * API 19 예약 변경의 업무 규칙 (R7·R8·R9·R11·R14, X11) — Repository·customer·item은 가짜(DB 없음).
 * 목적만 바뀐 경우 {@code version}이 실제로 한 번만 오르는지(R11), 벌크 삭제 뒤 재삽입, 동시 요청(R8)은 DB가 있어야
 * 확인되어 여기서 증명하지 못한다(미검증). 호출 순서·인자와 "어긋나면 아무것도 바꾸지 않는다"를 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationUpdateServiceTest {

    private static final long SHOP = 3L;
    private static final long VEHICLE = 4L;
    private static final long RES_ID = 5L;

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
        return new ReservationService(repository, itemRepository, customerReader, itemReader, staffReader, clock);
    }

    private static Reservation reservation(long id, LocalDateTime at, ReservationStatus status, int version) {
        Reservation r = Reservation.register(SHOP, VEHICLE, at, false, "기존 메모", 7L, 7L);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", version);
        return r;
    }

    private Reservation existing() {
        Reservation r = reservation(RES_ID, LocalDateTime.of(2026, 11, 26, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.of(r));
        return r;
    }

    private static ReservationUpdateRequest request(int version, LocalDateTime at, List<Long> itemIds, Boolean other,
                                                    String memo) {
        boolean hasPurpose = itemIds != null || other != null || memo != null;
        return new ReservationUpdateRequest(version, at,
            hasPurpose ? new ReservationCreateRequest.Purpose(itemIds, other, memo) : null);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    private static void assertInvalidInput(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(e.fieldErrors()).extracting("field").containsExactly(field);
        });
    }

    @Test
    @DisplayName("일시만 변경: 값이 바뀌고, 나머지(메모·기타)는 그대로, 응답은 08 19번 모양(reservedAt 포함), 목적 행은 건드리지 않는다")
    void changeTimeOnly() {
        Reservation r = existing();
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of(r));

        ReservationResponse response = service().update(SHOP, RES_ID,
            request(0, LocalDateTime.of(2026, 11, 28, 9, 0), null, null, null));

        assertThat(response.reservationId()).isEqualTo(RES_ID);
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.vehicleId()).isEqualTo(VEHICLE);
        assertThat(response.reservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 28, 9, 0));
        assertThat(r.getPurposeMemo()).isEqualTo("기존 메모");
        assertThat(r.isPurposeOther()).isFalse();
        verify(repository).saveAndFlush(r);
        verifyNoInteractions(itemRepository, itemReader);
    }

    @Test
    @DisplayName("예약 목적만 변경: 항목 검증 → 기존 목적 행 삭제 → 새 행 저장, 기타·메모 반영, 일시는 그대로")
    void changePurposeOnly() {
        Reservation r = existing();

        service().update(SHOP, RES_ID, request(0, null, List.of(1L, 4L), true, "새 메모"));

        // 일시를 보내지 않았으므로 같은 차량 유효 예약 검사는 하지 않는다
        verify(repository, never()).findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any());

        assertThat(r.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));
        assertThat(r.isPurposeOther()).isTrue();
        assertThat(r.getPurposeMemo()).isEqualTo("새 메모");
        InOrder order = inOrder(itemReader, itemRepository, repository);
        order.verify(itemReader).requireActiveItems(SHOP, List.of(1L, 4L), "purpose.itemIds");
        order.verify(itemRepository).deleteAllOf(SHOP, RES_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReservationItem>> saved = ArgumentCaptor.forClass(List.class);
        order.verify(itemRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(ReservationItem::getServiceItemId).containsExactly(1L, 4L);
        assertThat(saved.getValue()).extracting(ReservationItem::getReservationId).containsOnly(RES_ID);
        assertThat(saved.getValue()).extracting(ReservationItem::getShopId).containsOnly(SHOP);
        order.verify(repository).saveAndFlush(r);
    }

    @Test
    @DisplayName("빈 항목 목록을 보내면 목적 행을 모두 지우고 새로 넣지 않는다 / 메모는 빈 문자열이면 비운다")
    void clearItemsAndMemo() {
        Reservation r = existing();

        service().update(SHOP, RES_ID, request(0, null, List.of(), null, ""));

        verify(itemRepository).deleteAllOf(SHOP, RES_ID);
        verify(itemRepository, never()).saveAll(any());
        assertThat(r.getPurposeMemo()).isNull();
        // 일시를 보내지 않았으므로 같은 차량 유효 예약 확인은 하지 않는다
        verify(repository, never()).findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any());
    }

    @Test
    @DisplayName("R14 version만 보내고 바꿀 필드가 없음 → 400 INVALID_INPUT, 소유 확인(조회)보다 먼저, 아무것도 바뀌지 않음")
    void nothingToChange() {
        assertInvalidInput(() -> service().update(SHOP, RES_ID, request(0, null, null, null, null)), "reservedAt");

        verifyNoInteractions(repository, itemRepository, itemReader);
    }

    @Test
    @DisplayName("다른 정비소·없는 예약 → 404 NOT_FOUND (같은 응답), 저장 없음")
    void notOwned() {
        when(repository.findByIdAndShopId(RES_ID, 9L)).thenReturn(Optional.empty());

        assertCode(() -> service().update(9L, RES_ID,
            request(0, LocalDateTime.of(2026, 11, 28, 9, 0), null, null, null)), ErrorCode.NOT_FOUND);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("R9 방문 완료된 예약을 변경 → 409 INVALID_STATUS_TRANSITION, 예약·목적 그대로")
    void completedCannotBeChanged() {
        Reservation r = reservation(RES_ID, LocalDateTime.of(2026, 11, 26, 10, 0), ReservationStatus.COMPLETED, 1);
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.of(r));

        assertCode(() -> service().update(SHOP, RES_ID,
            request(1, LocalDateTime.of(2026, 11, 28, 9, 0), List.of(1L), null, null)),
            ErrorCode.INVALID_STATUS_TRANSITION);

        assertThat(r.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));
        verify(itemRepository, never()).deleteAllOf(anyLong(), anyLong());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("R8·R12 낡은 version → 409 CONCURRENT_MODIFICATION, 아무것도 바뀌지 않음(목적 행 삭제도 없음)")
    void staleVersion() {
        Reservation r = reservation(RES_ID, LocalDateTime.of(2026, 11, 26, 10, 0), ReservationStatus.RESERVED, 1);
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.of(r));

        assertCode(() -> service().update(SHOP, RES_ID,
            request(0, LocalDateTime.of(2026, 11, 28, 9, 0), List.of(1L), null, null)),
            ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(r.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));
        verify(itemRepository, never()).deleteAllOf(anyLong(), anyLong());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("R7 어제 날짜로 변경 → 400 RESERVATION_PAST_DATE / 오늘 이미 지난 시각은 허용")
    void pastDate() {
        Reservation r = existing();
        assertCode(() -> service().update(SHOP, RES_ID,
            request(0, LocalDateTime.of(2026, 11, 23, 10, 0), null, null, null)), ErrorCode.RESERVATION_PAST_DATE);
        verify(repository, never()).saveAndFlush(any());

        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of(r));
        service().update(SHOP, RES_ID, request(0, LocalDateTime.of(2026, 11, 24, 9, 0), null, null, null));
        assertThat(r.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 9, 0));
    }

    @Test
    @DisplayName("R1 30분 단위가 아니면 → 400 INVALID_INPUT(reservedAt)")
    void notHalfHour() {
        existing();

        assertInvalidInput(() -> service().update(SHOP, RES_ID,
            request(0, LocalDateTime.of(2026, 11, 28, 10, 15), null, null, null)), "reservedAt");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("X11 남의 정비소·쓰지 않는 항목 → item 계약의 400이 그대로 나가고, 기존 목적 행은 지워지지 않는다")
    void invalidItems() {
        existing();
        doThrow(BusinessException.invalidField("purpose.itemIds", "사용할 수 없는 항목입니다."))
            .when(itemReader).requireActiveItems(SHOP, List.of(99L), "purpose.itemIds");

        assertInvalidInput(() -> service().update(SHOP, RES_ID, request(0, null, List.of(99L), null, null)),
            "purpose.itemIds");

        verify(itemRepository, never()).deleteAllOf(anyLong(), anyLong());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("항목 중복 → 400 INVALID_INPUT(purpose.itemIds), item 계약 호출 없음")
    void duplicateItems() {
        existing();

        assertInvalidInput(() -> service().update(SHOP, RES_ID, request(0, null, List.of(1L, 1L), null, null)),
            "purpose.itemIds");

        verifyNoInteractions(itemReader);
    }

    @Test
    @DisplayName("같은 차량에 다른 유효 예약이 있으면 → 409 RESERVATION_ACTIVE_EXISTS + 그 예약 번호, 아무것도 바뀌지 않음")
    void otherActiveReservation() {
        Reservation r = existing();
        Reservation other = reservation(8L, LocalDateTime.of(2026, 11, 30, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any()))
            .thenReturn(List.of(r, other));

        assertThatThrownBy(() -> service().update(SHOP, RES_ID,
            request(0, LocalDateTime.of(2026, 11, 28, 9, 0), null, null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.RESERVATION_ACTIVE_EXISTS);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", 8L));
            });

        assertThat(r.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("자기 자신만 유효 예약이면 충돌이 아니다(자기 제외) — 변경 성공")
    void selfIsNotAConflict() {
        Reservation r = existing();
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of(r));

        service().update(SHOP, RES_ID, request(0, LocalDateTime.of(2026, 11, 29, 9, 30), null, null, null));

        verify(repository).saveAndFlush(r);
    }

    @Test
    @DisplayName("R18 일시를 바꿔 같은 담당 + 같은 시각의 다른 유효 예약과 겹치면 → 409 MECHANIC_SLOT_CONFLICT + 그 예약 번호, 아무것도 바뀌지 않음")
    void movingIntoAnotherSlotOfTheSameAssignee() {
        Reservation r = existing(); // 담당 7
        LocalDateTime target = LocalDateTime.of(2026, 11, 29, 9, 30);
        Reservation other = reservation(RES_ID + 1, target, ReservationStatus.RESERVED, 0);
        when(repository.findSameSlot(eq(SHOP), eq(7L), eq(target), any())).thenReturn(List.of(other));

        assertThatThrownBy(() -> service().update(SHOP, RES_ID, request(0, target, null, null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.MECHANIC_SLOT_CONFLICT);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", RES_ID + 1));
            });
        verify(repository, never()).saveAndFlush(any());
        assertThat(r.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));
    }

    @Test
    @DisplayName("담당 중복 확인에서 자기 자신은 뺀다 — 같은 시각으로 \"바꿔도\" 자기 예약과는 충돌이 아니다")
    void selfIsNotASlotConflict() {
        Reservation r = existing();
        LocalDateTime sameTime = r.getReservedAt();
        when(repository.findSameSlot(eq(SHOP), eq(7L), eq(sameTime), any())).thenReturn(List.of(r));

        service().update(SHOP, RES_ID, request(0, sameTime, null, null, null));

        verify(repository).saveAndFlush(r);
    }

    @Test
    @DisplayName("메모·목적만 바꿀 때는 담당 중복 확인도 하지 않는다 (일시를 보낼 때만, 08 19번)")
    void memoOnlyChangeSkipsSlotCheck() {
        existing();

        service().update(SHOP, RES_ID, request(0, null, null, null, "새 메모"));

        verify(repository, never()).findSameSlot(anyLong(), anyLong(), any(), any());
    }

    private Reservation pastReserved() {
        // 어제 이전 날짜의 RESERVED("처리 필요") — 유효 예약이 아니다(08 14·16: 날짜 ≥ 오늘)
        Reservation r = reservation(RES_ID, LocalDateTime.of(2026, 11, 20, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(RES_ID, SHOP)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("메모만 바꿀 때는 같은 차량 유효 예약 검사를 하지 않는다 — 지난 예약의 메모가 다른 예약 때문에 409가 되지 않는다")
    void memoOnlyChangeSkipsActiveCheck() {
        Reservation r = pastReserved();

        ReservationResponse response = service().update(SHOP, RES_ID, request(0, null, null, null, "정정 메모"));

        verify(repository, never()).findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any());
        verify(repository).saveAndFlush(r);
        assertThat(r.getPurposeMemo()).isEqualTo("정정 메모");
        assertThat(r.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
        assertThat(response.reservationId()).isEqualTo(RES_ID);
    }

    @Test
    @DisplayName("지난 예약의 일시를 오늘 이후로 옮기는데 같은 차량에 다른 유효 예약이 있으면 → 여전히 409 + 그 예약 번호")
    void movingPastReservationToFutureStillChecksActive() {
        Reservation r = pastReserved();
        Reservation other = reservation(8L, LocalDateTime.of(2026, 11, 30, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any()))
            .thenReturn(List.of(other));

        assertThatThrownBy(() -> service().update(SHOP, RES_ID,
            request(0, LocalDateTime.of(2026, 11, 28, 9, 0), null, null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.RESERVATION_ACTIVE_EXISTS);
                assertThat(e.details()).isEqualTo(Map.of("reservationId", 8L));
            });

        assertThat(r.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("지난 예약의 일시를 오늘 이후로 옮길 때 다른 유효 예약이 없으면 → 변경 성공")
    void movingPastReservationToFutureWithoutConflict() {
        Reservation r = pastReserved();
        when(repository.findByVehiclesAndStatusFrom(anyLong(), anyCollection(), any(), any())).thenReturn(List.of());

        service().update(SHOP, RES_ID, request(0, LocalDateTime.of(2026, 11, 28, 9, 0), null, null, null));

        assertThat(r.getReservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 28, 9, 0));
        verify(repository).saveAndFlush(r);
    }

    @Test
    @DisplayName("검증 순서는 등록(16)과 같다: 일시와 항목이 모두 틀리면 30분 단위(reservedAt) 오류가 먼저, 항목 오류·날짜 오류가 그다음")
    void validationOrderMatchesCreate() {
        existing();
        // 10:15(30분 단위 아님) + 어제 날짜 + 항목 중복 → 30분 단위 오류가 첫 번째
        assertInvalidInput(() -> service().update(SHOP, RES_ID,
            request(0, LocalDateTime.of(2026, 11, 23, 10, 15), List.of(1L, 1L), null, null)), "reservedAt");
        // 30분은 맞고 항목 중복 + 어제 날짜 → 항목 오류가 날짜 오류보다 먼저
        assertInvalidInput(() -> service().update(SHOP, RES_ID,
            request(0, LocalDateTime.of(2026, 11, 23, 10, 0), List.of(1L, 1L), null, null)), "purpose.itemIds");
    }
}
