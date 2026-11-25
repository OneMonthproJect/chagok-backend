package com.wanted.chagok.screen.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.domain.maintenance.api.VisitRef;
import com.wanted.chagok.domain.reservation.api.ReservationDetail;
import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.screen.booking.dto.PanelResponse;
import com.wanted.chagok.screen.booking.service.PanelService;
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
 * API 17 예약 상세 패널 조립 (08 17번, X3·D-54·D-63 B02) — 각 도메인 {@code api}는 가짜(DB 없음).
 * 조립만 본다: 어느 값이 어디서 오는지, 최근 방문 2회, 항목 줄 순서, 목적 항목 강조, 모든 호출이 토큰의 정비소로 가는지.
 * 실제 DB에서의 격리·값은 시드 DB 테스트가 본다(미검증).
 */
@ExtendWith(MockitoExtension.class)
class PanelServiceTest {

    private static final long SHOP = 3L;
    private static final long RESERVATION = 4L;
    private static final long VEHICLE = 10L;

    // 2026-11-24 15:00 (Asia/Seoul)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    @Mock
    private ReservationReader reservationReader;

    @Mock
    private CustomerReader customerReader;

    @Mock
    private MaintenanceReader maintenanceReader;

    @Mock
    private ItemReader itemReader;

    private PanelService service() {
        return new PanelService(reservationReader, customerReader, maintenanceReader, itemReader, clock);
    }

    private static ReservationDetail detail(List<Long> purposeItemIds) {
        return new ReservationDetail(RESERVATION, VEHICLE, LocalDateTime.of(2026, 11, 23, 15, 0), "RESERVED", true, 0,
            purposeItemIds, false, null);
    }

    private static ItemRef item(long id, String code, String name, int months, int sortOrder) {
        return new ItemRef(id, code, name, months, 10000, true, sortOrder);
    }

    private void stubVehicleAndCustomer() {
        when(customerReader.getOwnedVehicle(SHOP, VEHICLE))
            .thenReturn(new VehicleRef(VEHICLE, 10L, "55차0010", "쏘나타", 2018, 97000));
        when(customerReader.findVehicleSummaries(SHOP, List.of(VEHICLE)))
            .thenReturn(Map.of(VEHICLE, new VehicleSummary(VEHICLE, "55차0010", 10L, "오세훈", "010-0000-0010", true)));
    }

    @Test
    @DisplayName("조립: 예약·고객(전체 연락처·consent)·차량, 최근 방문 최대 2회와 전체 횟수, 목적 항목 이름, 모두 토큰의 정비소로 조회")
    void assemblesPanel() {
        ReservationDetail reservation = detail(List.of(6L));
        when(reservationReader.getOwnedDetail(SHOP, RESERVATION)).thenReturn(reservation);
        stubVehicleAndCustomer();
        VisitRef visit = new VisitRef("5eed-15", LocalDate.of(2026, 10, 24), null, "김정비", List.of(
            new VisitRef.VisitRecord(16L, 1L, "엔진오일", "REPLACE", LocalDate.of(2026, 10, 24), null)));
        when(maintenanceReader.findRecentVisits(SHOP, VEHICLE, 2)).thenReturn(List.of(visit));
        when(maintenanceReader.countVisits(SHOP, VEHICLE)).thenReturn(3L);
        when(itemReader.findItems(SHOP, List.of(6L))).thenReturn(Map.of(6L, item(6L, "BATTERY", "배터리", 36, 6)));
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of());
        when(maintenanceReader.findRecordsByVehicle(SHOP, List.of(VEHICLE))).thenReturn(Map.of());

        PanelResponse panel = service().getPanel(SHOP, RESERVATION);

        assertThat(panel.reservation().reservationId()).isEqualTo(RESERVATION);
        assertThat(panel.reservation().reservedAt()).isEqualTo(LocalDateTime.of(2026, 11, 23, 15, 0));
        assertThat(panel.reservation().status()).isEqualTo("RESERVED");
        assertThat(panel.reservation().needsAction()).isTrue();
        assertThat(panel.reservation().version()).isZero();
        assertThat(panel.reservation().purpose().items()).extracting("itemId", "name").containsExactly(
            tuple(6L, "배터리"));
        assertThat(panel.customer()).isEqualTo(new PanelResponse.CustomerBlock(10L, "오세훈", "010-0000-0010", true));
        assertThat(panel.vehicle()).isEqualTo(new PanelResponse.VehicleBlock(VEHICLE, "55차0010", "쏘나타", 2018, 97000));
        assertThat(panel.recentVisits()).containsExactly(visit);
        assertThat(panel.totalVisitCount()).isEqualTo(3L);
        verify(maintenanceReader).findRecentVisits(SHOP, VEHICLE, 2);
    }

    @Test
    @DisplayName("항목 상태: 주행거리 없이 날짜 기준으로 판정하고 sortOrder 순을 지킨다. 목적 항목은 강조만 하고 자리를 옮기지 않는다 (D-63 B02)")
    void itemStatusesInSortOrderWithHighlight() {
        when(reservationReader.getOwnedDetail(SHOP, RESERVATION)).thenReturn(detail(List.of(6L)));
        stubVehicleAndCustomer();
        when(itemReader.findItems(SHOP, List.of(6L))).thenReturn(Map.of(6L, item(6L, "BATTERY", "배터리", 36, 6)));
        // 쓰는 항목은 sortOrder 순으로 온다. 목적 항목(배터리)이 두 번째다 — 강조 때문에 앞으로 가면 안 된다
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of(
            item(1L, "ENGINE_OIL", "엔진오일", 6, 1), item(6L, "BATTERY", "배터리", 36, 6)));
        when(maintenanceReader.findRecordsByVehicle(SHOP, List.of(VEHICLE))).thenReturn(Map.of(VEHICLE, List.of(
            record(16L, 1L, LocalDate.of(2026, 10, 24)), record(7L, 6L, LocalDate.of(2023, 12, 14)))));

        PanelResponse panel = service().getPanel(SHOP, RESERVATION);

        List<PanelResponse.ItemStatusBlock> statuses = panel.itemStatuses();
        assertThat(statuses).extracting(PanelResponse.ItemStatusBlock::itemId).containsExactly(1L, 6L);
        assertThat(statuses).extracting(PanelResponse.ItemStatusBlock::highlighted).containsExactly(false, true);
        // 08 17번 예시와 같은 값: 엔진오일은 여유, 배터리는 임박(2023-12-14 + 36개월 = 2026-12-14)
        assertThat(statuses).extracting(PanelResponse.ItemStatusBlock::status)
            .containsExactly(ItemStatus.AHEAD, ItemStatus.SOON);
        assertThat(statuses.get(1).nextDueDate()).isEqualTo(LocalDate.of(2026, 12, 14));
        assertThat(statuses.get(1).nextDueMileage()).isNull();
        assertThat(statuses.get(1).baseRecord().date()).isEqualTo(LocalDate.of(2023, 12, 14));
        // 주행거리 없이 부르므로 km 기준 사유는 나오지 않는다
        assertThat(statuses).extracting(PanelResponse.ItemStatusBlock::dueReason).containsOnlyNulls();
    }

    @Test
    @DisplayName("목적 항목 이름은 id 순서와 상관없이 sortOrder 순이고, 비활성 항목 이름도 보인다")
    void purposeItemsInSortOrder() {
        when(reservationReader.getOwnedDetail(SHOP, RESERVATION)).thenReturn(detail(List.of(6L, 1L)));
        stubVehicleAndCustomer();
        ItemRef inactive = new ItemRef(1L, "ENGINE_OIL", "엔진오일", 6, 10000, false, 1);
        when(itemReader.findItems(SHOP, List.of(6L, 1L)))
            .thenReturn(Map.of(6L, item(6L, "BATTERY", "배터리", 36, 6), 1L, inactive));
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of(item(6L, "BATTERY", "배터리", 36, 6)));
        when(maintenanceReader.findRecordsByVehicle(SHOP, List.of(VEHICLE))).thenReturn(Map.of());

        PanelResponse panel = service().getPanel(SHOP, RESERVATION);

        assertThat(panel.reservation().purpose().items()).extracting(PanelResponse.PurposeItem::name)
            .containsExactly("엔진오일", "배터리");
        // 비활성 항목은 상태 줄에는 없다(쓰는 항목만, 10번과 같음)
        assertThat(panel.itemStatuses()).extracting(PanelResponse.ItemStatusBlock::itemId).containsExactly(6L);
    }

    @Test
    @DisplayName("목적 항목이 없는 예약: purpose.items는 빈 목록, 강조되는 항목 없음")
    void noPurposeItems() {
        when(reservationReader.getOwnedDetail(SHOP, RESERVATION)).thenReturn(detail(List.of()));
        stubVehicleAndCustomer();
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of(item(1L, "ENGINE_OIL", "엔진오일", 6, 1)));
        when(maintenanceReader.findRecordsByVehicle(SHOP, List.of(VEHICLE))).thenReturn(Map.of());

        PanelResponse panel = service().getPanel(SHOP, RESERVATION);

        assertThat(panel.reservation().purpose().items()).isEmpty();
        assertThat(panel.itemStatuses()).extracting(PanelResponse.ItemStatusBlock::highlighted).containsOnly(false);
    }

    @Test
    @DisplayName("미동의 고객(consent false)도 값이 그대로 전달된다 — 패널이 동의를 따로 판단하지 않는다")
    void declinedConsentIsCarried() {
        when(reservationReader.getOwnedDetail(SHOP, RESERVATION)).thenReturn(detail(List.of()));
        when(customerReader.getOwnedVehicle(SHOP, VEHICLE))
            .thenReturn(new VehicleRef(VEHICLE, 10L, "55차0010", "쏘나타", 2018, 97000));
        when(customerReader.findVehicleSummaries(SHOP, List.of(VEHICLE)))
            .thenReturn(Map.of(VEHICLE, new VehicleSummary(VEHICLE, "55차0010", 10L, "오세훈", "010-0000-0010", false)));
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of());
        when(maintenanceReader.findRecordsByVehicle(SHOP, List.of(VEHICLE))).thenReturn(Map.of());

        PanelResponse panel = service().getPanel(SHOP, RESERVATION);

        assertThat(panel.customer().consent()).isFalse();
    }

    @Test
    @DisplayName("차량은 우리 정비소 것인데 고객 요약이 없으면(데이터 불일치) → 404 NOT_FOUND, 정비·항목은 읽지 않는다")
    void customerSummaryMissing() {
        when(reservationReader.getOwnedDetail(SHOP, RESERVATION)).thenReturn(detail(List.of()));
        when(customerReader.getOwnedVehicle(SHOP, VEHICLE))
            .thenReturn(new VehicleRef(VEHICLE, 10L, "55차0010", "쏘나타", 2018, 97000));
        when(customerReader.findVehicleSummaries(SHOP, List.of(VEHICLE))).thenReturn(Map.of());

        assertThatThrownBy(() -> service().getPanel(SHOP, RESERVATION))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(maintenanceReader, itemReader);
    }

    @Test
    @DisplayName("X3 없거나 다른 정비소 예약 → 404 NOT_FOUND이고 다른 도메인은 부르지 않는다")
    void reservationNotOwned() {
        when(reservationReader.getOwnedDetail(SHOP, RESERVATION)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> service().getPanel(SHOP, RESERVATION))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(customerReader, maintenanceReader, itemReader);
    }

    @Test
    @DisplayName("예약의 차량이 우리 정비소 것이 아니면(getOwnedVehicle 404) 그대로 404, 정비·항목은 읽지 않는다")
    void vehicleNotOwned() {
        when(reservationReader.getOwnedDetail(SHOP, RESERVATION)).thenReturn(detail(List.of()));
        when(customerReader.getOwnedVehicle(SHOP, VEHICLE)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> service().getPanel(SHOP, RESERVATION))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(maintenanceReader, itemReader);
    }

    private static MaintenanceRecordRef record(long id, long itemId, LocalDate date) {
        return new MaintenanceRecordRef(id, VEHICLE, itemId, date, null, "REPLACE", true,
            date.atStartOfDay(), null);
    }
}
