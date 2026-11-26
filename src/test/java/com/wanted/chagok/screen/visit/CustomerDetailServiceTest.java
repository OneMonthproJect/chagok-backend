package com.wanted.chagok.screen.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerRef;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.screen.visit.dto.CustomerDetailResponse;
import com.wanted.chagok.screen.visit.dto.ItemStatusResponse;
import com.wanted.chagok.screen.visit.service.CustomerDetailService;
import com.wanted.chagok.screen.visit.service.ItemStatusService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API 07 조립 (04 F-CUS-04, 08 07, D-63 B01). 세 {@code api}는 목이고 {@code ItemStatusService}는 진짜라, 07의 개수가 API 10을
 * 주행거리 없이 부른 결과와 같은 판정에서 나오는지 본다. 상태 판정 자체는 cycle의 T1~T13이 맡는다. 오늘은 2026-11-24.
 */
class CustomerDetailServiceTest {

    private static final long SHOP = 1L;
    private static final long CUSTOMER = 10L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    // 엔진오일 6개월·10,000km, 에어클리너 12개월·20,000km
    private static final ItemRef OIL = new ItemRef(1, "ENGINE_OIL", "엔진오일", 6, 10_000, true, 1);
    private static final ItemRef AIR = new ItemRef(2, "AIR_CLEANER", "에어클리너", 12, 20_000, true, 2);

    private final CustomerReader customerReader = mock(CustomerReader.class);
    private final ItemReader itemReader = mock(ItemReader.class);
    private final MaintenanceReader maintenanceReader = mock(MaintenanceReader.class);
    private final ItemStatusService itemStatusService =
        new ItemStatusService(customerReader, itemReader, maintenanceReader, CLOCK);
    private final CustomerDetailService service = new CustomerDetailService(customerReader, itemStatusService);

    private static VehicleRef vehicle(long id, String plate, String model, Integer year, Integer mileage) {
        return new VehicleRef(id, CUSTOMER, plate, model, year, mileage);
    }

    private static MaintenanceRecordRef replace(long id, long vehicleId, long itemId, LocalDate date) {
        return new MaintenanceRecordRef(id, vehicleId, itemId, date, null, "REPLACE", true,
            LocalDateTime.of(date, LocalTime.NOON), null);
    }

    private void givenCustomer(VehicleRef... vehicles) {
        when(customerReader.getOwnedCustomer(SHOP, CUSTOMER))
            .thenReturn(new CustomerRef(CUSTOMER, "오세훈", "010-0000-0010", true, List.of(vehicles), 0));
    }

    /** 10번 차량: 엔진오일 도래·에어클리너 임박 / 11번: 기록 없음 / 12번: 엔진오일 여유. */
    private void givenItemsAndRecords() {
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of(OIL, AIR));
        when(maintenanceReader.findRecordsByVehicle(anyLong(), anyCollection())).thenReturn(Map.of(
            10L, List.of(replace(1, 10, 1, LocalDate.of(2026, 5, 23)), replace(2, 10, 2, LocalDate.of(2025, 12, 24))),
            11L, List.of(),
            12L, List.of(replace(3, 12, 1, LocalDate.of(2026, 9, 24)))));
    }

    @Test
    @DisplayName("고객 정보와 차량 목록을 옮기고, 차량마다 도래·임박 수를 붙인다 (비어 있는 연식·주행거리는 null 그대로)")
    void mapsCustomerAndCounts() {
        givenCustomer(vehicle(10, "55차0010", "쏘나타", 2018, 97_000), vehicle(11, "66카0011", "K3", null, null),
            vehicle(12, "77하0012", "아반떼", 2020, 1_000));
        givenItemsAndRecords();

        CustomerDetailResponse r = service.getCustomerDetail(SHOP, CUSTOMER);

        assertThat(r.customerId()).isEqualTo(CUSTOMER);
        assertThat(r.name()).isEqualTo("오세훈");
        assertThat(r.phone()).isEqualTo("010-0000-0010");
        assertThat(r.consent()).isTrue();
        assertThat(r.vehicles()).containsExactly(
            new CustomerDetailResponse.Vehicle(10, "55차0010", "쏘나타", 2018, 97_000, 1, 1),
            new CustomerDetailResponse.Vehicle(11, "66카0011", "K3", null, null, 0, 0),
            new CustomerDetailResponse.Vehicle(12, "77하0012", "아반떼", 2020, 1_000, 0, 0));
    }

    @Test
    @DisplayName("07의 개수는 같은 차량에 API 10을 주행거리 없이 부른 결과에서 DUE·SOON을 센 값과 같다")
    void countsEqualItemStatusWithoutMileage() {
        givenCustomer(vehicle(10, "55차0010", "쏘나타", 2018, 97_000), vehicle(11, "66카0011", "K3", null, null),
            vehicle(12, "77하0012", "아반떼", 2020, 1_000));
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of(OIL, AIR));
        when(maintenanceReader.findRecordsByVehicle(anyLong(), anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(1);
            Map<Long, List<MaintenanceRecordRef>> all = Map.of(
                10L, List.of(replace(1, 10, 1, LocalDate.of(2026, 5, 23)), replace(2, 10, 2, LocalDate.of(2025, 12, 24))),
                11L, List.of(),
                12L, List.of(replace(3, 12, 1, LocalDate.of(2026, 9, 24))));
            return all.entrySet().stream().filter(e -> ids.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        });

        CustomerDetailResponse detail = service.getCustomerDetail(SHOP, CUSTOMER);

        for (CustomerDetailResponse.Vehicle v : detail.vehicles()) {
            ItemStatusResponse api10 = itemStatusService.getItemStatus(SHOP, v.vehicleId(), null);
            assertThat(v.dueCount()).as("DUE 수 " + v.plateNumber())
                .isEqualTo((int) api10.items().stream().filter(i -> i.status() == ItemStatus.DUE).count());
            assertThat(v.soonCount()).as("SOON 수 " + v.plateNumber())
                .isEqualTo((int) api10.items().stream().filter(i -> i.status() == ItemStatus.SOON).count());
        }
    }

    @Test
    @DisplayName("항목과 정비 기록은 차량마다가 아니라 한 번씩만 조회한다 (N+1 없음)")
    void readsItemsAndRecordsOnce() {
        givenCustomer(vehicle(10, "55차0010", "쏘나타", 2018, 97_000), vehicle(11, "66카0011", "K3", null, null),
            vehicle(12, "77하0012", "아반떼", 2020, 1_000));
        givenItemsAndRecords();

        service.getCustomerDetail(SHOP, CUSTOMER);

        verify(itemReader, times(1)).findActiveItems(SHOP);
        verify(maintenanceReader, times(1)).findRecordsByVehicle(SHOP, List.of(10L, 11L, 12L));
        verify(customerReader, times(1)).getOwnedCustomer(SHOP, CUSTOMER);
    }

    @Test
    @DisplayName("차량이 없는 고객은 vehicles가 빈 목록이고 항목·정비 기록을 조회하지 않는다")
    void noVehicles() {
        givenCustomer();

        CustomerDetailResponse r = service.getCustomerDetail(SHOP, CUSTOMER);

        assertThat(r.vehicles()).isEmpty();
        verifyNoInteractions(itemReader, maintenanceReader);
    }

    @Test
    @DisplayName("X1: 없거나 남의 정비소 고객이면 404이고 이후 조회를 하지 않는다")
    void notOwnedCustomer() {
        when(customerReader.getOwnedCustomer(SHOP, CUSTOMER)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> service.getCustomerDetail(SHOP, CUSTOMER))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(itemReader, maintenanceReader);
    }

    @Test
    @DisplayName("기록 조회 결과에 없는 차량은 0·0으로 본다")
    void vehicleMissingFromRecordsMapIsZero() {
        givenCustomer(vehicle(10, "55차0010", "쏘나타", 2018, 97_000));
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of(OIL));
        when(maintenanceReader.findRecordsByVehicle(anyLong(), any())).thenReturn(Map.of());

        assertThat(service.getCustomerDetail(SHOP, CUSTOMER).vehicles())
            .extracting(CustomerDetailResponse.Vehicle::dueCount, CustomerDetailResponse.Vehicle::soonCount)
            .containsExactly(tuple(0, 0));
    }
}
