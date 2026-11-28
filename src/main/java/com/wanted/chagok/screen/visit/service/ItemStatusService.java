package com.wanted.chagok.screen.visit.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.cycle.api.CycleNoteKind;
import com.wanted.chagok.domain.cycle.api.CycleNoteRecord;
import com.wanted.chagok.domain.cycle.api.CycleNotes;
import com.wanted.chagok.domain.cycle.api.CycleRecord;
import com.wanted.chagok.domain.cycle.api.CycleRule;
import com.wanted.chagok.domain.cycle.api.ItemCycleCalculator;
import com.wanted.chagok.domain.cycle.api.ItemCycleResult;
import com.wanted.chagok.domain.cycle.api.ItemStatus;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.web.MileageRange;
import com.wanted.chagok.screen.visit.dto.ItemStatusResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
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
        if (MileageRange.isOutOfRange(mileage)) {
            throw BusinessException.invalidField("mileage", "0 이상 2,000,000 이하의 정수여야 합니다.");
        }
        // 없거나 다른 정비소 차량이면 여기서 404 (X6)
        customerReader.getOwnedVehicle(shopId, vehicleId);

        LocalDate today = LocalDate.now(clock);
        List<ItemRef> items = itemReader.findActiveItems(shopId);
        Map<Long, List<MaintenanceRecordRef>> byVehicle =
            maintenanceReader.findRecordsByVehicle(shopId, List.of(vehicleId));
        List<ItemStatusResponse.Item> result = statuses(items,
            byVehicle.getOrDefault(vehicleId, List.<MaintenanceRecordRef>of()), today, mileage);
        return new ItemStatusResponse(vehicleId, today, mileage, result);
    }

    /**
     * 차량 여러 대의 도래({@code DUE})·임박({@code SOON}) 항목 수를 한 번에 센다 (API 07 고객 상세의 {@code dueCount}·{@code soonCount}).
     * API 10을 주행거리 없이 부른 결과와 같은 기준이라 같은 판정({@link #statuses})을 쓴다. 항목과 정비 기록은 각각 한 번의 조회로 가져온다
     * (차량마다 부르지 않음). 차량 소속 확인은 호출하는 쪽이 이미 했다고 본다(소속이 확인된 차량 id만 넘긴다).
     * 같은 패키지의 {@code CustomerDetailService}만 쓰도록 package-private이다. 입력이 비어 있으면 아무것도 조회하지 않고 빈 Map. 기록이 없는 차량도 키가 있고 0·0이다(전부 미확인).
     */
    Map<Long, StatusCount> countStatuses(long shopId, Collection<Long> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            return Map.of();
        }
        LocalDate today = LocalDate.now(clock);
        List<ItemRef> items = itemReader.findActiveItems(shopId);
        Map<Long, List<MaintenanceRecordRef>> byVehicle = maintenanceReader.findRecordsByVehicle(shopId, vehicleIds);
        Map<Long, StatusCount> result = new LinkedHashMap<>();
        for (Long vehicleId : vehicleIds) {
            Map<Long, List<MaintenanceRecordRef>> recordsByItem =
                byItem(byVehicle.getOrDefault(vehicleId, List.<MaintenanceRecordRef>of()));
            // 도래·임박 수만 필요하므로 거절·주의 꼬리표(judgeNotes)는 판정하지 않는다
            int due = 0;
            int soon = 0;
            for (ItemRef item : items) {
                ItemStatus status = calculate(item, recordsByItem, today, null).status();
                if (status == ItemStatus.DUE) {
                    due++;
                } else if (status == ItemStatus.SOON) {
                    soon++;
                }
            }
            result.put(vehicleId, new StatusCount(due, soon));
        }
        return result;
    }

    /**
     * 한 차량의 사용 항목별 상태와 거절·주의 꼬리표. 날짜·km 비교와 꼬리표 판정은 {@link ItemCycleCalculator} 한 곳이고,
     * 여기서는 값을 옮겨 담기만 한다. 꼬리표는 상태를 바꾸지 않는다 (04 F-CYC-02).
     */
    private List<ItemStatusResponse.Item> statuses(List<ItemRef> items, List<MaintenanceRecordRef> records,
        LocalDate today, Integer mileage) {
        Map<Long, List<MaintenanceRecordRef>> recordsByItem = byItem(records);
        return items.stream()
            .map(item -> {
                ItemCycleResult r = calculate(item, recordsByItem, today, mileage);
                CycleNotes notes = calculator.judgeNotes(
                    recordsByItem.getOrDefault(item.itemId(), List.of()).stream()
                        .map(ItemStatusService::toNoteRecord).toList());
                return new ItemStatusResponse.Item(item.itemId(), item.code(), item.name(), r.status(),
                    r.dueReason(), r.baseRecord(), r.nextDueDate(), r.nextDueMileage(),
                    notes.lastDecline(), notes.lastCaution());
            })
            .toList();
    }

    private ItemCycleResult calculate(ItemRef item, Map<Long, List<MaintenanceRecordRef>> recordsByItem,
        LocalDate today, Integer mileage) {
        List<CycleRecord> records = recordsByItem.getOrDefault(item.itemId(), List.of()).stream()
            .map(ItemStatusService::toCycleRecord).toList();
        return calculator.calculate(new CycleRule(item.cycleMonths(), item.cycleKm()), records, today, mileage);
    }

    private static Map<Long, List<MaintenanceRecordRef>> byItem(List<MaintenanceRecordRef> records) {
        return records.stream().collect(Collectors.groupingBy(MaintenanceRecordRef::itemId));
    }

    /** 한 차량의 도래·임박 항목 수 (API 07). */
    record StatusCount(int dueCount, int soonCount) {
    }

    private static CycleRecord toCycleRecord(MaintenanceRecordRef r) {
        return new CycleRecord(r.date(), r.mileage(), r.resetsCycle(), r.createdAt(), r.recordId(), r.type());
    }

    /** 꼬리표 판정 재료. 거절·점검 주의 여부는 maintenance가 알려 주고(api 메서드), 판정은 cycle이 한다. */
    private static CycleNoteRecord toNoteRecord(MaintenanceRecordRef r) {
        CycleNoteKind kind = r.isDecline() ? CycleNoteKind.DECLINE
            : r.isInspectCaution() ? CycleNoteKind.INSPECT_CAUTION : CycleNoteKind.OTHER;
        return new CycleNoteRecord(r.date(), r.createdAt(), r.recordId(), r.resetsCycle(), kind, r.memo());
    }
}
