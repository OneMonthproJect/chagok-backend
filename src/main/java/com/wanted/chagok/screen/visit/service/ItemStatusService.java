package com.wanted.chagok.screen.visit.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.cycle.api.CycleRecord;
import com.wanted.chagok.domain.cycle.api.CycleRule;
import com.wanted.chagok.domain.cycle.api.ItemCycleCalculator;
import com.wanted.chagok.domain.cycle.api.ItemCycleResult;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.screen.visit.dto.ItemStatusResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 10 항목 상태·저장 전 미리보기 (04 F-VIS-01, 08 10). 여러 {@code api}를 읽어 응답만 조립한다 — 표 없음, 저장·상태
 * 변경 없음. 상태 판정은 {@link ItemCycleCalculator} 한 곳이며 여기서 날짜·km를 비교하지 않는다 (CONVENTIONS 4장).
 */
@Service
@Transactional(readOnly = true)
public class ItemStatusService {

    private static final int MAX_MILEAGE = 2_000_000;

    private final CustomerReader customerReader;
    private final ItemReader itemReader;
    private final MaintenanceReader maintenanceReader;
    private final Clock clock;
    private final ItemCycleCalculator calculator = ItemCycleCalculator.standard();

    public ItemStatusService(CustomerReader customerReader, ItemReader itemReader,
        MaintenanceReader maintenanceReader, Clock clock) {
        this.customerReader = customerReader;
        this.itemReader = itemReader;
        this.maintenanceReader = maintenanceReader;
        this.clock = clock;
    }

    /** @param mileage 방문 중 입력한 주행거리. null이면 날짜 기준만 판정한다 (T11) */
    public ItemStatusResponse getItemStatus(long shopId, long vehicleId, Integer mileage) {
        if (mileage != null && (mileage < 0 || mileage > MAX_MILEAGE)) {
            throw BusinessException.invalidField("mileage", "0 이상 2,000,000 이하의 정수여야 합니다.");
        }
        // 없거나 다른 정비소 차량이면 여기서 404 (X6)
        customerReader.getOwnedVehicle(shopId, vehicleId);

        LocalDate today = LocalDate.now(clock);
        List<ItemRef> items = itemReader.findActiveItems(shopId);
        Map<Long, List<MaintenanceRecordRef>> byVehicle =
            maintenanceReader.findRecordsByVehicle(shopId, List.of(vehicleId));
        Map<Long, List<CycleRecord>> recordsByItem = byVehicle.getOrDefault(vehicleId, List.<MaintenanceRecordRef>of())
            .stream()
            .collect(Collectors.groupingBy(MaintenanceRecordRef::itemId,
                Collectors.mapping(ItemStatusService::toCycleRecord, Collectors.toList())));

        List<ItemStatusResponse.Item> result = items.stream()
            .map(item -> {
                ItemCycleResult r = calculator.calculate(new CycleRule(item.cycleMonths(), item.cycleKm()),
                    recordsByItem.getOrDefault(item.itemId(), List.of()), today, mileage);
                return new ItemStatusResponse.Item(item.itemId(), item.code(), item.name(), r.status(),
                    r.dueReason(), r.baseRecord(), r.nextDueDate(), r.nextDueMileage());
            })
            .toList();
        return new ItemStatusResponse(vehicleId, today, mileage, result);
    }

    private static CycleRecord toCycleRecord(MaintenanceRecordRef r) {
        return new CycleRecord(r.date(), r.mileage(), r.resetsCycle(), r.createdAt(), r.recordId(), r.type());
    }
}
