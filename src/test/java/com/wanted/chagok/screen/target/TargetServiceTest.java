package com.wanted.chagok.screen.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.domain.reservation.api.ReservationRef;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.screen.target.dto.TargetListResponse;
import com.wanted.chagok.screen.target.service.TargetService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * API 14 재점검 대상 목록 조립 (08 14번, 04 F-TGT-01) — 각 도메인 {@code api}는 가짜(DB 없음).
 * 조립만 본다: 대상 판정(도래·임박만, 미확인·여유 제외), 줄 안 항목 순서, 정렬, 예약 있음, 모든 호출이 토큰의 정비소로 가는지.
 * 실제 DB에서의 격리·값은 시드 DB 테스트가 본다.
 *
 * <p>오늘은 2026-11-24. 항목: 엔진오일(6개월, sortOrder 1), 브레이크패드(12개월, 2), 배터리(36개월, 3). 임박 = 다음 정비일이 한 달 안.
 */
@ExtendWith(MockitoExtension.class)
class TargetServiceTest {

    private static final long SHOP = 3L;
    private static final long ENGINE_OIL = 1L;
    private static final long BRAKE_PAD = 4L;
    private static final long BATTERY = 6L;

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    @Mock
    private CustomerReader customerReader;

    @Mock
    private MaintenanceReader maintenanceReader;

    @Mock
    private ItemReader itemReader;

    @Mock
    private ReservationReader reservationReader;

    private TargetService service() {
        return new TargetService(customerReader, maintenanceReader, itemReader, reservationReader, clock);
    }

    private static VehicleSummary vehicle(long id, String plate, String customerName) {
        return new VehicleSummary(id, plate, id * 10, customerName, "010-0000-000" + id, true);
    }

    private static MaintenanceRecordRef record(long recordId, long vehicleId, long itemId, LocalDate date) {
        return new MaintenanceRecordRef(recordId, vehicleId, itemId, date, null, "REPLACE", true, date.atStartOfDay(), null);
    }

    private void stub(List<VehicleSummary> vehicles, Map<Long, List<MaintenanceRecordRef>> records,
                      Map<Long, ReservationRef> active) {
        List<Long> ids = vehicles.stream().map(VehicleSummary::vehicleId).toList();
        when(customerReader.findConsentedVehicles(SHOP)).thenReturn(vehicles);
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of(
            new ItemRef(ENGINE_OIL, "ENGINE_OIL", "엔진오일", 6, 10000, true, 1),
            new ItemRef(BRAKE_PAD, "BRAKE_PAD", "브레이크패드", 12, 20000, true, 2),
            new ItemRef(BATTERY, "BATTERY", "배터리", 36, 60000, true, 3)));
        when(maintenanceReader.findRecordsByVehicle(SHOP, ids)).thenReturn(records);
        when(reservationReader.findActiveByVehicleIds(SHOP, ids)).thenReturn(active);
    }

    @Test
    @DisplayName("대상 판정과 정렬: 도래·임박만 나오고(미확인·여유 제외), 도래가 먼저 가장 이른 다음 정비일 순, 같으면 차량 id 순, 예약 있음 표시")
    void targetsAndOrder() {
        List<VehicleSummary> vehicles = List.of(
            vehicle(1, "12가0001", "김민수"), vehicle(2, "34나0002", "이영희"), vehicle(3, "56다0003", "박철수"),
            vehicle(4, "90마0005", "정우성"), vehicle(5, "78라0004", "최지은"), vehicle(6, "55차0010", "오세훈"));
        stub(vehicles, Map.of(
            1L, List.of(record(1, 1, ENGINE_OIL, LocalDate.of(2026, 5, 17))),   // 다음 11-17: 도래
            2L, List.of(record(2, 2, ENGINE_OIL, LocalDate.of(2026, 5, 14))),   // 다음 11-14: 도래(더 이른 날짜)
            3L, List.of(record(3, 3, BRAKE_PAD, LocalDate.of(2025, 12, 14))),   // 다음 2026-12-14: 임박
            // 4: 기록 없음 → 미확인, 대상 아님
            5L, List.of(record(5, 5, ENGINE_OIL, LocalDate.of(2026, 10, 24))),  // 다음 2027-04-24: 여유
            6L, List.of(record(6, 6, BATTERY, LocalDate.of(2023, 12, 14)))),    // 다음 2026-12-14: 임박
            Map.of(2L, new ReservationRef(3L, 2L, LocalDateTime.of(2026, 11, 26, 10, 0))));

        TargetListResponse response = service().getTargets(SHOP);

        assertThat(response.asOf()).isEqualTo(LocalDate.of(2026, 11, 24));
        // 도래 둘(11-14 이영희 → 11-17 김민수) 다음에 임박 둘. 임박 둘은 날짜가 같아(12-14) 차량 id 순(박철수 3 → 오세훈 6)
        assertThat(response.items()).extracting(TargetListResponse.Item::plateNumber)
            .containsExactly("34나0002", "12가0001", "56다0003", "55차0010");

        TargetListResponse.Item first = response.items().get(0);
        assertThat(first.customerName()).isEqualTo("이영희");
        assertThat(first.phone()).isEqualTo("010-0000-0002");
        assertThat(first.targetItems()).hasSize(1);
        assertThat(first.targetItems().get(0).itemId()).isEqualTo(ENGINE_OIL);
        assertThat(first.targetItems().get(0).name()).isEqualTo("엔진오일");
        assertThat(first.targetItems().get(0).status()).isEqualTo(ItemStatus.DUE);
        assertThat(first.targetItems().get(0).nextDueDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(first.targetItems().get(0).baseDate()).isEqualTo(LocalDate.of(2026, 5, 14));
        assertThat(first.activeReservation().reservationId()).isEqualTo(3L);
        assertThat(first.activeReservation().reservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));

        // 예약이 없는 차량은 null이고, 임박 항목은 SOON으로 나온다
        assertThat(response.items().get(1).activeReservation()).isNull();
        assertThat(response.items().get(2).targetItems().get(0).status()).isEqualTo(ItemStatus.SOON);
        assertThat(response.items().get(3).targetItems().get(0).baseDate()).isEqualTo(LocalDate.of(2023, 12, 14));
    }

    @Test
    @DisplayName("한 차량에 도래와 임박이 함께: 줄은 하나, targetItems는 두 항목이 sortOrder 순이고 여유 항목은 빠진다")
    void oneRowWithDueAndSoonItems() {
        List<VehicleSummary> vehicles = List.of(vehicle(1, "12가0001", "김민수"), vehicle(2, "34나0002", "이영희"));
        stub(vehicles, Map.of(
            1L, List.of(record(1, 1, ENGINE_OIL, LocalDate.of(2026, 5, 1)),        // 다음 11-01: 도래
                record(2, 1, BRAKE_PAD, LocalDate.of(2025, 12, 20)),               // 다음 12-20: 임박
                record(3, 1, BATTERY, LocalDate.of(2026, 1, 10))),                 // 다음 2029-01-10: 여유
            2L, List.of(record(4, 2, BRAKE_PAD, LocalDate.of(2025, 12, 10)))),     // 다음 12-10: 임박만
            Map.of());

        TargetListResponse response = service().getTargets(SHOP);

        assertThat(response.items()).extracting(TargetListResponse.Item::vehicleId).containsExactly(1L, 2L);
        assertThat(response.items().get(0).targetItems()).extracting(TargetListResponse.TargetItem::itemId)
            .containsExactly(ENGINE_OIL, BRAKE_PAD);
        assertThat(response.items().get(0).targetItems()).extracting(TargetListResponse.TargetItem::status)
            .containsExactly(ItemStatus.DUE, ItemStatus.SOON);
    }

    @Test
    @DisplayName("도래가 있는 차량은 임박만 있는 차량보다 먼저 온다 (다음 정비일 순보다 도래 여부가 먼저)")
    void dueBeforeSoon() {
        // 차량 1: 임박(다음 정비일 12-10, 가까움), 차량 2: 도래(다음 정비일 11-23, 어제). 차량 id는 1이 앞이지만 도래가 먼저다
        List<VehicleSummary> vehicles = List.of(vehicle(1, "12가0001", "김민수"), vehicle(2, "34나0002", "이영희"));
        stub(vehicles, Map.of(
            1L, List.of(record(1, 1, BRAKE_PAD, LocalDate.of(2025, 12, 10))),
            2L, List.of(record(2, 2, ENGINE_OIL, LocalDate.of(2026, 5, 23)))),
            Map.of());

        TargetListResponse response = service().getTargets(SHOP);

        assertThat(response.items()).extracting(TargetListResponse.Item::vehicleId).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("동의한 고객의 차량은 customer.api가 한 곳에서 정한다 — 서비스는 consent를 다시 거르지 않고 요약을 따로 조회하지 않는다")
    void consentIsDecidedByCustomerApi() {
        // 반환 요약의 consent가 false여도 서비스는 걸러내지 않는다(걸러내는 것은 계약 구현의 책임이고 이 값은 그대로 믿는다)
        VehicleSummary notConsented = new VehicleSummary(1, "12가0001", 10, "김민수", "010-0000-0001", false);
        stub(List.of(notConsented), Map.of(1L, List.of(record(1, 1, ENGINE_OIL, LocalDate.of(2026, 5, 1)))), Map.of());

        TargetListResponse response = service().getTargets(SHOP);

        assertThat(response.items()).hasSize(1);
        // 동의한 고객의 차량은 findConsentedVehicles 한 번으로만 읽고, 요약을 따로 조회해 동의를 다시 확인하지 않는다
        verify(customerReader).findConsentedVehicles(SHOP);
        verifyNoMoreInteractions(customerReader);
    }

    @Test
    @DisplayName("동의 고객의 차량이 없으면 빈 목록이고 정비·항목·예약은 읽지 않는다. asOf는 오늘")
    void emptyWhenNoConsentedVehicles() {
        when(customerReader.findConsentedVehicles(SHOP)).thenReturn(List.of());

        TargetListResponse response = service().getTargets(SHOP);

        assertThat(response.items()).isEmpty();
        assertThat(response.asOf()).isEqualTo(LocalDate.of(2026, 11, 24));
        verifyNoInteractions(maintenanceReader, itemReader, reservationReader);
    }

    @Test
    @DisplayName("대상이 하나도 없으면(전부 미확인·여유) 빈 목록")
    void emptyWhenNobodyIsTarget() {
        List<VehicleSummary> vehicles = List.of(vehicle(1, "12가0001", "김민수"), vehicle(2, "34나0002", "이영희"));
        stub(vehicles, Map.of(2L, List.of(record(1, 2, ENGINE_OIL, LocalDate.of(2026, 10, 24)))), Map.of());

        assertThat(service().getTargets(SHOP).items()).isEmpty();
    }

    @Test
    @DisplayName("모든 조회는 토큰의 정비소로 가고, 차량·정비·예약은 건별이 아니라 한 번에 읽는다")
    void readsOnceWithTheShopId() {
        List<VehicleSummary> vehicles = List.of(vehicle(1, "12가0001", "김민수"), vehicle(2, "34나0002", "이영희"));
        stub(vehicles, Map.of(), Map.of());

        service().getTargets(SHOP);

        verify(customerReader).findConsentedVehicles(SHOP);
        verify(itemReader).findActiveItems(SHOP);
        verify(maintenanceReader).findRecordsByVehicle(SHOP, List.of(1L, 2L));
        verify(reservationReader).findActiveByVehicleIds(SHOP, List.of(1L, 2L));
    }
}
