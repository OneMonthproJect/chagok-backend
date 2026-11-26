package com.wanted.chagok.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.reservation.api.ReservationRef;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationStartRequest;
import com.wanted.chagok.domain.reservation.entity.Reservation;
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
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * API 39 방문 접수 서비스 단위 테스트 (R21~R23, X14). Repository·다른 도메인 계약은 가짜(DB 없음).
 * 실제 저장·{@code @Version} 증가와 차량 행 잠금은 DB가 필요한데 이 파일에는 없다(DB 테스트는 미검증으로 보고한다).
 */
@ExtendWith(MockitoExtension.class)
class ReservationStartServiceTest {

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    @Mock
    private ReservationRepository repository;

    @Mock
    private ReservationItemRepository itemRepository;

    @Mock
    private CustomerReader customerReader;

    @Mock
    private CustomerWriter customerWriter;

    @Mock
    private ItemReader itemReader;
    @Mock
    private StaffReader staffReader;

    private ReservationService service() {
        return new ReservationService(repository, itemRepository, customerReader, customerWriter, itemReader, staffReader, clock);
    }

    private static Reservation reservation(long id, long shopId, long vehicleId, LocalDateTime at,
                                           ReservationStatus status, int version) {
        Reservation r = Reservation.register(shopId, vehicleId, at, false, null, 7L);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "version", version);
        return r;
    }

    private static VehicleRef vehicle(Integer modelYear, Integer latestMileage) {
        return new VehicleRef(4L, 2L, "12가3456", "소나타", modelYear, latestMileage);
    }

    private static ReservationStartRequest request(int version, Integer modelYear, Integer mileage) {
        return new ReservationStartRequest(version, modelYear, mileage);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    @DisplayName("R21 방문 접수: 소유 확인 → 차량 잠금 → 연식·주행거리 반영 → 진행중 전이 → 저장(flush), 응답 status IN_PROGRESS")
    void start() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));
        when(customerWriter.getOwnedVehicleForVisit(3L, 4L)).thenReturn(vehicle(2018, 54000));

        ReservationResponse response = service().start(3L, 1L, request(0, null, null));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(response.status()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(response.reservationId()).isEqualTo(1L);
        assertThat(response.vehicleId()).isEqualTo(4L);
        InOrder order = inOrder(customerWriter, repository);
        order.verify(customerWriter).getOwnedVehicleForVisit(3L, 4L);
        order.verify(customerWriter).applyVisit(3L, 4L, null, null, "modelYear");
        order.verify(repository).saveAndFlush(r);
    }

    @Test
    @DisplayName("D-58 차량 연식·주행거리가 비어 있고 요청에 있으면 customer에 넘겨 차량에 저장한다")
    void startFillsEmptyVehicleInfo() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));
        when(customerWriter.getOwnedVehicleForVisit(3L, 4L)).thenReturn(vehicle(null, null));

        service().start(3L, 1L, request(0, 2019, 41000));

        verify(customerWriter).applyVisit(3L, 4L, 2019, 41000, "modelYear");
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("D-58 차량 값이 비어 있는데 요청에도 없으면 400 INVALID_INPUT(modelYear·mileage 필드별), 아무것도 바꾸지 않는다")
    void startRequiresVehicleInfoWhenEmpty() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));
        when(customerWriter.getOwnedVehicleForVisit(3L, 4L)).thenReturn(vehicle(null, null));

        assertThatThrownBy(() -> service().start(3L, 1L, request(0, null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting("field").containsExactly("modelYear", "mileage");
            });

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        verify(customerWriter, never()).applyVisit(anyLong(), anyLong(), any(), any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("연식만 비어 있으면 연식만 요구한다 (주행거리는 차량에 있음)")
    void startRequiresOnlyMissingField() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));
        when(customerWriter.getOwnedVehicleForVisit(3L, 4L)).thenReturn(vehicle(null, 54000));

        assertThatThrownBy(() -> service().start(3L, 1L, request(0, null, null)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting("field").containsExactly("modelYear"));
    }

    @Test
    @DisplayName("X14 없는 예약·다른 정비소 예약 → 404 NOT_FOUND, 차량·저장은 건드리지 않는다")
    void startNotOwned() {
        when(repository.findByIdAndShopId(9L, 3L)).thenReturn(Optional.empty());

        assertCode(() -> service().start(3L, 9L, request(0, null, null)), ErrorCode.NOT_FOUND);

        verifyNoInteractions(customerWriter);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이미 진행중·완료·취소인 예약 → 409 INVALID_STATUS_TRANSITION, 차량은 건드리지 않는다")
    void startWrongStatus() {
        for (ReservationStatus s : new ReservationStatus[] {ReservationStatus.IN_PROGRESS, ReservationStatus.COMPLETED,
            ReservationStatus.CANCELED, ReservationStatus.NO_SHOW}) {
            Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), s, 0);
            when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));

            assertCode(() -> service().start(3L, 1L, request(0, 2019, 41000)), ErrorCode.INVALID_STATUS_TRANSITION);

            assertThat(r.getStatus()).isEqualTo(s);
        }
        verifyNoInteractions(customerWriter);
    }

    @Test
    @DisplayName("R12 version이 다르면 409 CONCURRENT_MODIFICATION, 차량 값은 반영하지 않는다")
    void startVersionMismatch() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, 2);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));

        assertCode(() -> service().start(3L, 1L, request(1, 2019, 41000)), ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        verifyNoInteractions(customerWriter);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("시각이 지난 예약(처리 필요)도 방문 접수할 수 있다 (D-23)")
    void startPastTime() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 9, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));
        when(customerWriter.getOwnedVehicleForVisit(3L, 4L)).thenReturn(vehicle(2018, 54000));

        service().start(3L, 1L, request(0, null, null));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("어제 날짜의 RESERVED(처리 필요)도 접수할 수 있다 (D-23, 오늘 지난 시각뿐 아니라 날짜가 지난 예약)")
    void startYesterdayReservation() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 23, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));
        when(customerWriter.getOwnedVehicleForVisit(3L, 4L)).thenReturn(vehicle(2018, 54000));

        service().start(3L, 1L, request(0, null, null));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("차량에 값이 있어도 요청의 연식·주행거리는 customer에 그대로 넘긴다 (채울지·갱신할지는 customer 규칙)")
    void startPassesRequestValuesThrough() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));
        when(customerWriter.getOwnedVehicleForVisit(3L, 4L)).thenReturn(vehicle(2018, 54000));

        service().start(3L, 1L, request(0, 2020, 60000));

        verify(customerWriter).applyVisit(3L, 4L, 2020, 60000, "modelYear");
    }

    @Test
    @DisplayName("범위 위반으로 customer가 400을 던지면 저장하지 않고 예약은 RESERVED 그대로 (트랜잭션 롤백 전제)")
    void startRangeErrorDoesNotSave() {
        Reservation r = reservation(1, 3, 4, LocalDateTime.of(2026, 11, 24, 10, 0), ReservationStatus.RESERVED, 0);
        when(repository.findByIdAndShopId(1L, 3L)).thenReturn(Optional.of(r));
        when(customerWriter.getOwnedVehicleForVisit(3L, 4L)).thenReturn(vehicle(null, null));
        org.mockito.Mockito.doThrow(BusinessException.invalidField("modelYear", "범위"))
            .when(customerWriter).applyVisit(3L, 4L, 1979, 1000, "modelYear");

        assertThatThrownBy(() -> service().start(3L, 1L, request(0, 1979, 1000)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting("field").containsExactly("modelYear"));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("R23 어제 날짜의 진행중 예약도 유효 예약 — 날짜와 무관하게 후보에 들어온다")
    void inProgressYesterdayIsActive() {
        Reservation yesterday =
            reservation(5, 3, 4, LocalDateTime.of(2026, 11, 23, 10, 0), ReservationStatus.IN_PROGRESS, 1);
        when(repository.findByVehiclesAndStatusFrom(any(), any(), any(), any())).thenReturn(List.of());
        when(repository.findByVehiclesAndStatus(3L, Set.of(4L), ReservationStatus.IN_PROGRESS))
            .thenReturn(List.of(yesterday));

        Map<Long, ReservationRef> active = service().findActiveByVehicleIds(3L, Set.of(4L));

        assertThat(active).containsOnlyKeys(4L);
        assertThat(active.get(4L).reservationId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("진행중과 예약이 함께 있으면 더 이른 예약이 대표다 (이른 순 병합)")
    void activeMergesInProgressAndReserved() {
        Reservation reserved =
            reservation(6, 3, 4, LocalDateTime.of(2026, 11, 26, 10, 0), ReservationStatus.RESERVED, 0);
        Reservation inProgress =
            reservation(5, 3, 4, LocalDateTime.of(2026, 11, 23, 10, 0), ReservationStatus.IN_PROGRESS, 1);
        when(repository.findByVehiclesAndStatusFrom(any(), any(), any(), any())).thenReturn(List.of(reserved));
        when(repository.findByVehiclesAndStatus(3L, Set.of(4L), ReservationStatus.IN_PROGRESS))
            .thenReturn(List.of(inProgress));

        Map<Long, ReservationRef> active = service().findActiveByVehicleIds(3L, Set.of(4L));

        assertThat(active.get(4L).reservationId()).isEqualTo(5L);
    }
}
