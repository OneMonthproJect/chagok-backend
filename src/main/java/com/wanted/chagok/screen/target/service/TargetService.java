package com.wanted.chagok.screen.target.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.cycle.api.CycleRecord;
import com.wanted.chagok.domain.cycle.api.CycleRule;
import com.wanted.chagok.domain.cycle.api.ItemCycleCalculator;
import com.wanted.chagok.domain.cycle.api.ItemCycleResult;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.domain.reservation.api.ReservationRef;
import com.wanted.chagok.screen.target.dto.TargetListResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 14 재점검 대상 목록 조립 (08 14번, 04 F-TGT-01). 저장·상태 변경 없이 다른 도메인의 {@code api}를 읽어 한 응답으로 만든다
 * (CONVENTIONS 5장). 상태 판정은 {@code cycle}만 부르고 여기서 날짜·km를 다시 비교하지 않는다.
 *
 * <p>대상 = 안내 수신에 동의한 고객의 차량 중 도래·임박 항목이 하나 이상인 차량이다. 동의 판단은 {@code customer.api}가 한 곳에서 하므로
 * 여기서 {@code consent}를 다시 거르지 않는다. 미확인 항목은 대상이 아니다. 항목 상태는 10번을 주행거리 없이 부른 결과와 같다(날짜 기준).
 * {@code screen}끼리는 서로 부르지 않으므로(CONVENTIONS 3장) {@code screen/visit}·{@code screen/booking}의 서비스를 쓰지 않고 같은
 * {@code api}를 직접 읽는다.
 */
@Service
@Transactional(readOnly = true)
public class TargetService {

    /**
     * 정렬: 도래가 있는 차량 먼저, 그다음 가장 이른 다음 정비일 순 (08 14번). 둘이 같으면 차량 id 순으로 한다 — 08에 정해진 바가 없어
     * 결과가 매번 같도록 정한 값이다.
     */
    private static final Comparator<Row> ORDER = Comparator
        .comparing((Row row) -> !row.hasDue())
        .thenComparing(Row::earliestDueDate)
        .thenComparingLong(row -> row.item().vehicleId());

    private final CustomerReader customerReader;
    private final MaintenanceReader maintenanceReader;
    private final ItemReader itemReader;
    private final ReservationReader reservationReader;
    private final Clock clock;
    private final ItemCycleCalculator calculator = ItemCycleCalculator.standard();

    public TargetService(CustomerReader customerReader, MaintenanceReader maintenanceReader, ItemReader itemReader,
                         ReservationReader reservationReader, Clock clock) {
        this.customerReader = customerReader;
        this.maintenanceReader = maintenanceReader;
        this.itemReader = itemReader;
        this.reservationReader = reservationReader;
        this.clock = clock;
    }

    // LIMIT: 정비소당 차량 수백 대 이하 가정(08 14번, 쪽 나누기 없음). 넘으면 customer.api 목록과 함께 기간·쪽 나누기를 다시 정한다
    public TargetListResponse getTargets(long shopId) {
        LocalDate today = LocalDate.now(clock);
        List<VehicleSummary> vehicles = customerReader.findConsentedVehicles(shopId);
        if (vehicles.isEmpty()) {
            return new TargetListResponse(today, List.of());
        }

        List<Long> vehicleIds = vehicles.stream().map(VehicleSummary::vehicleId).toList();
        List<ItemRef> items = itemReader.findActiveItems(shopId);
        Map<Long, List<MaintenanceRecordRef>> recordsByVehicle = maintenanceReader.findRecordsByVehicle(shopId, vehicleIds);
        Map<Long, ReservationRef> activeByVehicle = reservationReader.findActiveByVehicleIds(shopId, vehicleIds);

        List<TargetListResponse.Item> targets = vehicles.stream()
            .map(vehicle -> toRow(vehicle, items, recordsByVehicle.getOrDefault(vehicle.vehicleId(), List.of()),
                activeByVehicle.get(vehicle.vehicleId()), today))
            .filter(Objects::nonNull)
            .sorted(ORDER)
            .map(Row::item)
            .toList();
        return new TargetListResponse(today, targets);
    }

    /** 도래·임박 항목이 하나도 없으면(미확인·여유뿐) 대상이 아니므로 null. */
    private Row toRow(VehicleSummary vehicle, List<ItemRef> items, List<MaintenanceRecordRef> records,
                      ReservationRef activeReservation, LocalDate today) {
        Map<Long, List<CycleRecord>> recordsByItem = records.stream()
            .collect(Collectors.groupingBy(MaintenanceRecordRef::itemId,
                Collectors.mapping(TargetService::toCycleRecord, Collectors.toList())));

        List<TargetListResponse.TargetItem> targetItems = new ArrayList<>();
        boolean hasDue = false;
        LocalDate earliestDueDate = null;
        for (ItemRef item : items) {
            ItemCycleResult result = calculator.calculate(new CycleRule(item.cycleMonths(), item.cycleKm()),
                recordsByItem.getOrDefault(item.itemId(), List.of()), today, null);
            if (result.status() != ItemStatus.DUE && result.status() != ItemStatus.SOON) {
                continue;
            }
            targetItems.add(new TargetListResponse.TargetItem(item.itemId(), item.name(), result.status(),
                result.nextDueDate(), result.baseRecord().date()));
            hasDue |= result.status() == ItemStatus.DUE;
            if (earliestDueDate == null || result.nextDueDate().isBefore(earliestDueDate)) {
                earliestDueDate = result.nextDueDate();
            }
        }
        if (targetItems.isEmpty()) {
            return null;
        }
        TargetListResponse.ActiveReservation reservation = activeReservation == null ? null
            : new TargetListResponse.ActiveReservation(activeReservation.reservationId(), activeReservation.reservedAt());
        return new Row(new TargetListResponse.Item(vehicle.customerId(), vehicle.customerName(), vehicle.phone(),
            vehicle.vehicleId(), vehicle.plateNumber(), targetItems, reservation), hasDue, earliestDueDate);
    }

    private static CycleRecord toCycleRecord(MaintenanceRecordRef r) {
        return new CycleRecord(r.date(), r.mileage(), r.resetsCycle(), r.createdAt(), r.recordId(), r.type());
    }

    private record Row(TargetListResponse.Item item, boolean hasDue, LocalDate earliestDueDate) {
    }
}
