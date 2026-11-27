package com.wanted.chagok.screen.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.cycle.api.DueReason;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.screen.visit.dto.ItemStatusResponse;
import com.wanted.chagok.screen.visit.service.ItemStatusService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API 10 조립 (04 F-VIS-01, T8~T11, 08 X6). 세 {@code api}는 목이라 DB가 없다. 상태 판정 자체는 cycle의 T1~T13이 맡고,
 * 여기서는 호출·옮겨 담기·격리·검증만 본다. 오늘은 2026-11-24(08 예시와 같음).
 */
class ItemStatusServiceTest {

    private static final long SHOP = 1L;
    private static final long VEHICLE = 4L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    // 엔진오일 6개월·10,000km, 에어클리너 12개월·20,000km (순서는 sortOrder)
    private static final ItemRef OIL = new ItemRef(1, "ENGINE_OIL", "엔진오일", 6, 10_000, true, 1);
    private static final ItemRef AIR = new ItemRef(2, "AIR_CLEANER", "에어클리너", 12, 20_000, true, 2);

    private final CustomerReader customerReader = mock(CustomerReader.class);
    private final ItemReader itemReader = mock(ItemReader.class);
    private final MaintenanceReader maintenanceReader = mock(MaintenanceReader.class);
    private final ItemStatusService service =
        new ItemStatusService(customerReader, itemReader, maintenanceReader, CLOCK);

    private void givenItems(ItemRef... items) {
        when(itemReader.findActiveItems(SHOP)).thenReturn(List.of(items));
    }

    private void givenRecords(MaintenanceRecordRef... records) {
        when(maintenanceReader.findRecordsByVehicle(SHOP, List.of(VEHICLE)))
            .thenReturn(Map.of(VEHICLE, List.of(records)));
    }

    private static MaintenanceRecordRef replace(long id, long itemId, LocalDate date, Integer mileage) {
        return new MaintenanceRecordRef(id, VEHICLE, itemId, date, mileage, "REPLACE", true,
            LocalDateTime.of(date, LocalTime.NOON), null, null);
    }

    private static ItemStatusResponse.Item item(ItemStatusResponse r, long itemId) {
        return r.items().stream().filter(i -> i.itemId() == itemId).findFirst().orElseThrow();
    }

    /** 주기를 다시 시작하지 않는 기록(거절·점검). inspectResult는 점검일 때만 값이 있다. */
    private static MaintenanceRecordRef note(long id, long itemId, LocalDate date, String type, String inspectResult,
                                             String memo) {
        return new MaintenanceRecordRef(id, VEHICLE, itemId, date, null, type, false,
            LocalDateTime.of(date, LocalTime.NOON), memo, inspectResult);
    }

    @Test
    @DisplayName("T14 교체 뒤 거절이 가장 최근이면 lastDecline(날짜·메모), lastCaution은 null, 상태는 그대로")
    void t14_declineAfterReplaceShowsLastDecline() {
        givenItems(OIL);
        givenRecords(replace(1, 1, LocalDate.of(2026, 5, 24), null),
            note(2, 1, LocalDate.of(2026, 10, 24), "DECLINE", null, "다음에 하겠다고 함"));

        ItemStatusResponse.Item i = item(service.getItemStatus(SHOP, VEHICLE, null), 1);

        assertThat(i.lastDecline().date()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(i.lastDecline().memo()).isEqualTo("다음에 하겠다고 함");
        assertThat(i.lastCaution()).isNull();
        // 꼬리표는 상태를 바꾸지 않는다: 교체 6개월 → 오늘(11-24) 도래, 기준 기록은 거절이 아니라 교체
        assertThat(i.status()).isEqualTo(ItemStatus.DUE);
        assertThat(i.baseRecord().date()).isEqualTo(LocalDate.of(2026, 5, 24));
    }

    @Test
    @DisplayName("T14 거절만 있어도(기준 기록 없음) lastDecline이 붙고 상태는 미확인·baseRecord는 null (08 10)")
    void t14_declineOnlyStillShowsLastDecline() {
        givenItems(OIL);
        givenRecords(note(1, 1, LocalDate.of(2026, 10, 24), "DECLINE", null, null));

        ItemStatusResponse.Item i = item(service.getItemStatus(SHOP, VEHICLE, null), 1);

        assertThat(i.lastDecline().date()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(i.lastDecline().memo()).isNull();
        assertThat(i.status()).isEqualTo(ItemStatus.UNKNOWN);
        assertThat(i.baseRecord()).isNull();
    }

    @Test
    @DisplayName("T14 거절 뒤에 같은 항목이 다시 기록되면(교체) 표시가 사라진다")
    void t14_laterRecordClearsDecline() {
        givenItems(OIL);
        givenRecords(replace(1, 1, LocalDate.of(2026, 5, 24), null),
            note(2, 1, LocalDate.of(2026, 10, 24), "DECLINE", null, null),
            replace(3, 1, LocalDate.of(2026, 11, 20), null));

        ItemStatusResponse.Item i = item(service.getItemStatus(SHOP, VEHICLE, null), 1);

        assertThat(i.lastDecline()).isNull();
        assertThat(i.lastCaution()).isNull();
    }

    @Test
    @DisplayName("T15 가장 최근 기록이 점검·주의이면 lastCaution(날짜·메모), lastDecline은 null, 상태는 그대로")
    void t15_inspectCautionShowsLastCaution() {
        givenItems(OIL);
        givenRecords(replace(1, 1, LocalDate.of(2026, 9, 24), null),
            note(2, 1, LocalDate.of(2026, 10, 24), "INSPECT", "CAUTION", "앞 타이어 마모"));

        ItemStatusResponse.Item i = item(service.getItemStatus(SHOP, VEHICLE, null), 1);

        assertThat(i.lastCaution().date()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(i.lastCaution().memo()).isEqualTo("앞 타이어 마모");
        assertThat(i.lastDecline()).isNull();
        assertThat(i.status()).isEqualTo(ItemStatus.AHEAD);
        assertThat(i.baseRecord().date()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    @DisplayName("T15 점검·양호이면 표시 없음. 기록이 없는 항목도 두 값 모두 null")
    void t15_inspectGoodAndNoRecordsShowNothing() {
        givenItems(OIL, AIR);
        givenRecords(replace(1, 1, LocalDate.of(2026, 9, 24), null),
            note(2, 1, LocalDate.of(2026, 10, 24), "INSPECT", "GOOD", "이상 없음"));

        ItemStatusResponse r = service.getItemStatus(SHOP, VEHICLE, null);

        assertThat(item(r, 1).lastDecline()).isNull();
        assertThat(item(r, 1).lastCaution()).isNull();
        assertThat(item(r, 2).lastDecline()).isNull();
        assertThat(item(r, 2).lastCaution()).isNull();
    }

    @Test
    @DisplayName("T8 교체 45,000km·날짜 여유, 방문 55,000km → 도래(KM), km 경계 포함")
    void t8_kmBoundaryIsDue() {
        givenItems(OIL);
        givenRecords(replace(1, 1, LocalDate.of(2026, 9, 24), 45_000));

        ItemStatusResponse r = service.getItemStatus(SHOP, VEHICLE, 55_000);

        assertThat(r.asOf()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(r.inputMileage()).isEqualTo(55_000);
        assertThat(item(r, 1).status()).isEqualTo(ItemStatus.DUE);
        assertThat(item(r, 1).dueReason()).isEqualTo(DueReason.KM);
        assertThat(item(r, 1).nextDueMileage()).isEqualTo(55_000);
        assertThat(item(r, 1).nextDueDate()).isEqualTo(LocalDate.of(2027, 3, 24));
        assertThat(item(r, 1).baseRecord().type()).isEqualTo("REPLACE");
    }

    @Test
    @DisplayName("날짜·km가 모두 도래이면 dueReason은 DATE (08 10)")
    void bothDueReportsDate() {
        givenItems(OIL);
        givenRecords(replace(1, 1, LocalDate.of(2026, 5, 1), 45_000));

        ItemStatusResponse.Item i = item(service.getItemStatus(SHOP, VEHICLE, 60_000), 1);
        assertThat(i.status()).isEqualTo(ItemStatus.DUE);
        assertThat(i.dueReason()).isEqualTo(DueReason.DATE);
    }

    @Test
    @DisplayName("T9 방문 54,999km → 여유(날짜 기준 유지)")
    void t9_justBelowKmIsAhead() {
        givenItems(OIL);
        givenRecords(replace(1, 1, LocalDate.of(2026, 9, 24), 45_000));

        assertThat(item(service.getItemStatus(SHOP, VEHICLE, 54_999), 1).status()).isEqualTo(ItemStatus.AHEAD);
    }

    @Test
    @DisplayName("T10 기준 기록에 km가 없으면 km 판정 생략, 날짜 기준 상태")
    void t10_baseWithoutMileageSkipsKm() {
        givenItems(OIL);
        givenRecords(replace(1, 1, LocalDate.of(2026, 9, 24), null));

        ItemStatusResponse.Item i = item(service.getItemStatus(SHOP, VEHICLE, 99_999), 1);
        assertThat(i.status()).isEqualTo(ItemStatus.AHEAD);
        assertThat(i.nextDueMileage()).isNull();
    }

    @Test
    @DisplayName("T11 mileage 미입력 → 날짜 기준만, inputMileage는 null")
    void t11_noMileageUsesDateOnly() {
        givenItems(OIL);
        givenRecords(replace(1, 1, LocalDate.of(2026, 9, 24), 45_000));

        ItemStatusResponse r = service.getItemStatus(SHOP, VEHICLE, null);

        assertThat(r.inputMileage()).isNull();
        assertThat(item(r, 1).status()).isEqualTo(ItemStatus.AHEAD);
    }

    @Test
    @DisplayName("기록이 없는 항목은 미확인이고, 항목은 sortOrder 순으로 모두 나온다")
    void itemsWithoutRecordsAreUnknownInOrder() {
        givenItems(OIL, AIR);
        givenRecords(replace(1, 1, LocalDate.of(2026, 9, 24), 45_000));

        ItemStatusResponse r = service.getItemStatus(SHOP, VEHICLE, null);

        assertThat(r.items()).extracting(ItemStatusResponse.Item::itemId).containsExactly(1L, 2L);
        ItemStatusResponse.Item air = item(r, 2);
        assertThat(air.status()).isEqualTo(ItemStatus.UNKNOWN);
        assertThat(air.dueReason()).isNull();
        assertThat(air.baseRecord()).isNull();
        assertThat(air.nextDueDate()).isNull();
        assertThat(air.nextDueMileage()).isNull();
    }

    @Test
    @DisplayName("차량에 기록이 전혀 없어도(맵에 키 없음) 전부 미확인")
    void noRecordsAtAll() {
        givenItems(OIL);
        when(maintenanceReader.findRecordsByVehicle(SHOP, List.of(VEHICLE))).thenReturn(Map.of());

        assertThat(item(service.getItemStatus(SHOP, VEHICLE, 10), 1).status()).isEqualTo(ItemStatus.UNKNOWN);
    }

    @Test
    @DisplayName("mileage 경계 0·2,000,000은 허용")
    void mileageBoundsAreAllowed() {
        givenItems(OIL);
        givenRecords();

        assertThat(service.getItemStatus(SHOP, VEHICLE, 0).inputMileage()).isZero();
        assertThat(service.getItemStatus(SHOP, VEHICLE, 2_000_000).inputMileage()).isEqualTo(2_000_000);
    }

    @Test
    @DisplayName("mileage가 음수·2,000,001이면 400 INVALID_INPUT + mileage fieldErrors, 아무것도 조회하지 않는다")
    void mileageOutOfRangeIsInvalidInput() {
        for (int bad : new int[] {-1, 2_000_001}) {
            assertThatThrownBy(() -> service.getItemStatus(SHOP, VEHICLE, bad))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.fieldErrors()).extracting("field").containsExactly("mileage");
                });
        }
        verifyNoMoreInteractions(customerReader, itemReader, maintenanceReader);
    }

    @Test
    @DisplayName("X6 남의 정비소·없는 차량은 404이고 기록·항목을 조회하지 않는다")
    void x6_notOwnedVehicleIs404() {
        when(customerReader.getOwnedVehicle(SHOP, VEHICLE)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> service.getItemStatus(SHOP, VEHICLE, 55_000))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoMoreInteractions(itemReader, maintenanceReader);
    }

    @Test
    @DisplayName("토큰의 shopId로 소속 확인·항목·기록을 한 번씩만 조회한다 (읽기 계약만 사용)")
    void readsEachContractOnceWithShopId() {
        givenItems(OIL, AIR);
        givenRecords();

        service.getItemStatus(SHOP, VEHICLE, null);

        verify(customerReader).getOwnedVehicle(SHOP, VEHICLE);
        verify(itemReader).findActiveItems(SHOP);
        verify(maintenanceReader).findRecordsByVehicle(SHOP, List.of(VEHICLE));
        verifyNoMoreInteractions(customerReader, itemReader, maintenanceReader);
    }
}
