package com.wanted.chagok.domain.maintenance.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.domain.maintenance.api.VisitRef;
import com.wanted.chagok.domain.maintenance.dto.VisitListResponse;
import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecord;
import com.wanted.chagok.domain.maintenance.repository.MaintenanceRecordRepository;
import com.wanted.chagok.global.web.PageLimit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문 이력 조회(API 11, F-MNT-01)와 다른 도메인에 주는 읽기 계약 구현. 저장(API 12)은 #42가 메서드를 더한다.
 * 차량 소속은 {@code customer.api}, 항목 이름은 {@code item.api}로만 확인한다. 모든 조회는 {@code shopId}와 함께 한다(I-3).
 */
@Service
@Transactional(readOnly = true)
public class MaintenanceService implements MaintenanceReader {

    private final MaintenanceRecordRepository records;
    private final CustomerReader customers;
    private final ItemReader items;

    public MaintenanceService(MaintenanceRecordRepository records, CustomerReader customers, ItemReader items) {
        this.records = records;
        this.customers = customers;
        this.items = items;
    }

    /** API 11. 없거나 남의 정비소 차량이면 404(X2)이고, 기록이 없으면 {@code items: []}. */
    public VisitListResponse listVisits(long shopId, long vehicleId, PageLimit limit) {
        customers.getOwnedVehicle(shopId, vehicleId);

        // LIMIT: 한 차량의 기록 전부를 읽어 메모리에서 묶는다. 차량당 기록은 수십~수백 건 이하 가정.
        // 넘으면 visit_key 단위 집계 쿼리로 바꾼다.
        List<VisitGroup> visits = visitsNewestFirst(records.findByShopIdAndVehicleId(shopId, vehicleId));
        long total = visits.size();
        int totalPages = (int) ((total + limit.size() - 1) / limit.size());
        long from = Math.min((long) limit.page() * limit.size(), total);
        long to = Math.min(from + limit.size(), total);

        List<VisitRef> page = toRefs(shopId, visits.subList((int) from, (int) to));
        return new VisitListResponse(page, limit.page(), limit.size(), total, totalPages);
    }

    @Override
    public Map<Long, List<MaintenanceRecordRef>> findRecordsByVehicle(long shopId, Collection<Long> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = vehicleIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, List<MaintenanceRecordRef>> result = new HashMap<>();
        ids.forEach(id -> result.put(id, new ArrayList<>()));
        records.findByShopIdAndVehicleIdIn(shopId, ids).stream()
            .sorted(Comparator.comparing(MaintenanceRecord::getRecordDate)
                .thenComparing(MaintenanceRecord::getCreatedAt)
                .thenComparing(MaintenanceRecord::getId))
            .forEach(r -> result.get(r.getVehicleId()).add(toRecordRef(r)));
        return result;
    }

    @Override
    public List<VisitRef> findRecentVisits(long shopId, long vehicleId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<VisitGroup> visits = visitsNewestFirst(records.findByShopIdAndVehicleId(shopId, vehicleId));
        return toRefs(shopId, visits.subList(0, Math.min(limit, visits.size())));
    }

    @Override
    public long countVisits(long shopId, long vehicleId) {
        // 외부 기록만 있는 방문은 세지 않는다 (05 POL-45)
        return visitsNewestFirst(records.findByShopIdAndVehicleId(shopId, vehicleId)).stream()
            .filter(VisitGroup::hasShopRecord)
            .count();
    }

    private static MaintenanceRecordRef toRecordRef(MaintenanceRecord r) {
        return new MaintenanceRecordRef(r.getId(), r.getVehicleId(), r.getServiceItemId(), r.getRecordDate(),
            r.getMileage(), r.getRecordType().name(), r.getRecordType().resetsCycle(), r.getCreatedAt(), r.getMemo());
    }

    /** {@code visit_key}로 묶어 최신순: visitDate 내림차순, 같으면 나중에 저장한 방문 먼저 (08 11). */
    private static List<VisitGroup> visitsNewestFirst(List<MaintenanceRecord> rows) {
        Map<String, List<MaintenanceRecord>> byKey = new LinkedHashMap<>();
        for (MaintenanceRecord row : rows) {
            byKey.computeIfAbsent(row.getVisitKey(), k -> new ArrayList<>()).add(row);
        }
        return byKey.values().stream()
            .map(VisitGroup::of)
            .sorted(Comparator.comparing(VisitGroup::visitDate).reversed()
                .thenComparing(Comparator.comparing(VisitGroup::savedAt).reversed())
                .thenComparing(Comparator.comparingLong(VisitGroup::lastRecordId).reversed()))
            .toList();
    }

    /** 방문 묶음에 항목 이름·담당 정비사 이름을 붙인다. 항목 이름은 한 번의 조회로 가져온다(건별 호출 금지). */
    private List<VisitRef> toRefs(long shopId, List<VisitGroup> visits) {
        if (visits.isEmpty()) {
            return List.of();
        }
        Set<Long> itemIds = visits.stream()
            .flatMap(v -> v.rows().stream())
            .map(MaintenanceRecord::getServiceItemId)
            .collect(Collectors.toSet());
        Map<Long, ItemRef> itemRefs = items.findItems(shopId, itemIds);
        Set<Long> staffIds = visits.stream().map(VisitGroup::staffId).collect(Collectors.toSet());
        Map<Long, String> staffNames = findStaffNames(shopId, staffIds);

        return visits.stream()
            .map(v -> new VisitRef(v.visitKey(), v.visitDate(), v.mileage(), staffNames.get(v.staffId()),
                v.rows().stream()
                    .map(r -> new VisitRef.VisitRecord(r.getId(), r.getServiceItemId(),
                        itemRefs.containsKey(r.getServiceItemId()) ? itemRefs.get(r.getServiceItemId()).name() : "",
                        r.getRecordType().name(), r.getRecordDate(), r.getMemo()))
                    .toList()))
            .toList();
    }

    /**
     * 담당 정비사 이름 일괄 조회. staff 표는 account 도메인 소유라 {@code account.api} 계약이 필요한데
     * 아직 없다(chagok-backend#44에서 계약 요청 중). 계약이 머지되면 여기서 그 계약을 불러 이름을 채운다.
     * 그 전까지 API 11의 {@code staffName}은 null이다(08 11과 다름, 미완).
     */
    private Map<Long, String> findStaffNames(long shopId, Collection<Long> staffIds) {
        // TODO(#44): account.api 계약 머지 후 교체. 다른 도메인 Entity·Repository는 직접 쓰지 않는다 (CONVENTIONS 1장 1번)
        return Map.of();
    }

    /** 같은 {@code visit_key}의 기록 묶음과 그 묶음의 파생 값. */
    private record VisitGroup(String visitKey, List<MaintenanceRecord> rows, LocalDate visitDate, Integer mileage,
                              LocalDateTime savedAt, long lastRecordId, long staffId, boolean hasShopRecord) {

        static VisitGroup of(List<MaintenanceRecord> group) {
            List<MaintenanceRecord> rows = group.stream()
                .sorted(Comparator.comparing(MaintenanceRecord::getId))
                .toList();
            List<MaintenanceRecord> shopRows = rows.stream().filter(r -> !r.getRecordType().isExternal()).toList();
            // visitDate·mileage: 외부가 아닌 기록 기준, 외부만 있으면 그 기록 기준 (08 11)
            List<MaintenanceRecord> basis = shopRows.isEmpty() ? rows : shopRows;
            LocalDate visitDate = basis.stream().map(MaintenanceRecord::getRecordDate).max(Comparator.naturalOrder()).orElseThrow();
            Integer mileage = basis.stream().map(MaintenanceRecord::getMileage).filter(Objects::nonNull).findFirst().orElse(null);
            LocalDateTime savedAt = rows.stream().map(MaintenanceRecord::getCreatedAt).max(Comparator.naturalOrder()).orElseThrow();
            long lastId = rows.get(rows.size() - 1).getId();
            return new VisitGroup(rows.get(0).getVisitKey(), rows, visitDate, mileage, savedAt, lastId,
                rows.get(0).getStaffId(), !shopRows.isEmpty());
        }
    }
}
