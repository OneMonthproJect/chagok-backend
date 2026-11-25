package com.wanted.chagok.domain.maintenance.service;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.domain.maintenance.api.VisitRef;
import com.wanted.chagok.domain.maintenance.dto.VisitListResponse;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveResponse;
import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecord;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.repository.MaintenanceRecordRepository;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.response.ApiResponse.FieldErrorDetail;
import com.wanted.chagok.global.web.PageLimit;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문 이력 조회(API 11, F-MNT-01), 방문·정비 기록 저장(API 12)과 다른 도메인에 주는 읽기 계약 구현.
 * 차량 소속은 {@code customer.api}, 항목은 {@code item.api}, 담당 이름은 {@code account.api}로만 확인한다. 차량 값 반영도 {@code customer.api}의
 * 쓰기 계약({@link CustomerWriter})으로만 한다. 모든 조회는 {@code shopId}와 함께 한다(I-3).
 */
@Service
@Transactional(readOnly = true)
public class MaintenanceService implements MaintenanceReader {

    private static final String NOT_OPEN = "아직 사용할 수 없는 입력입니다.";

    private final MaintenanceRecordRepository records;
    private final CustomerReader customers;
    private final CustomerWriter vehicleWriter;
    private final ItemReader items;
    private final StaffReader staff;
    private final Clock clock;

    public MaintenanceService(MaintenanceRecordRepository records, CustomerReader customers,
                              CustomerWriter vehicleWriter, ItemReader items, StaffReader staff, Clock clock) {
        this.records = records;
        this.customers = customers;
        this.vehicleWriter = vehicleWriter;
        this.items = items;
        this.staff = staff;
        this.clock = clock;
    }

    /**
     * API 12. 한 방문의 기록 행들을 한 {@code visit_key}로 저장하고, 같은 트랜잭션에서 차량의 연식·최근 주행거리를 반영한다
     * (하나라도 실패하면 아무것도 저장하지 않는다). 담당은 저장한 계정이다(P0-핵심). 검증 순서는 차량 소속(404, X5) →
     * P0-핵심에서 열지 않은 입력 → 방문일 → 항목 중복 → 쓰는 항목(X11) → 차량이 비어 있을 때 필수(D-58) → 저장.
     */
    @Transactional
    public VisitSaveResponse saveVisit(long shopId, long staffId, long vehicleId, VisitSaveRequest request) {
        VehicleRef vehicle = customers.getOwnedVehicle(shopId, vehicleId);

        rejectNotOpenInputs(request);
        if (request.visitDate().isAfter(LocalDate.now(clock))) {
            throw BusinessException.invalidField("visitDate", "오늘 이전 날짜만 입력할 수 있습니다.");
        }
        requireNoDuplicateItems(request.records());
        items.requireActiveItems(shopId,
            request.records().stream().map(VisitSaveRequest.RecordInput::itemId).toList(), "records[].itemId");

        Integer modelYear = request.vehicle() == null ? null : request.vehicle().modelYear();
        requireVehicleInfo(vehicle, modelYear, request.mileage());
        // 연식·주행거리의 범위 검증과 반영 규칙은 customer 한 곳에 있다. 실패하면 이 트랜잭션이 통째로 취소된다.
        vehicleWriter.applyVisit(shopId, vehicleId, modelYear, request.mileage());

        String visitKey = UUID.randomUUID().toString();
        List<MaintenanceRecord> rows = request.records().stream()
            .map(r -> MaintenanceRecord.record(shopId, vehicleId, r.itemId(), visitKey, request.visitDate(),
                request.mileage(), r.type(), staffId, blankToNull(r.memo())))
            .toList();
        return new VisitSaveResponse(vehicleId, visitKey, records.saveAll(rows).size());
    }

    /** P0-핵심에서는 {@code REPLACE}·{@code INSPECT}만 받는다. 나머지 입력(외부·거절·점검 결과·담당 지정)은 모두 400 (08 12). */
    private static void rejectNotOpenInputs(VisitSaveRequest request) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (request.mechanicId() != null) {
            errors.add(new FieldErrorDetail("mechanicId", NOT_OPEN));
        }
        List<VisitSaveRequest.RecordInput> rows = request.records();
        for (int i = 0; i < rows.size(); i++) {
            VisitSaveRequest.RecordInput r = rows.get(i);
            String at = "records[" + i + "].";
            if (r.type() == RecordType.EXTERNAL || r.type() == RecordType.DECLINE) {
                errors.add(new FieldErrorDetail(at + "type", NOT_OPEN));
            }
            if (r.externalDate() != null) {
                errors.add(new FieldErrorDetail(at + "externalDate", NOT_OPEN));
            }
            if (r.externalMileage() != null) {
                errors.add(new FieldErrorDetail(at + "externalMileage", NOT_OPEN));
            }
            if (r.inspectResult() != null) {
                errors.add(new FieldErrorDetail(at + "inspectResult", NOT_OPEN));
            }
        }
        throwIfAny(errors);
    }

    /** 같은 방문에 같은 항목을 두 번 넣을 수 없다 (05 POL-57). 요청 안의 중복이라 DB 제약은 없다(07 3.7). */
    private static void requireNoDuplicateItems(List<VisitSaveRequest.RecordInput> rows) {
        Set<Long> seen = new HashSet<>();
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!seen.add(rows.get(i).itemId())) {
                errors.add(new FieldErrorDetail("records[" + i + "].itemId", "같은 항목을 두 번 넣을 수 없습니다."));
            }
        }
        throwIfAny(errors);
    }

    /** 차량에 연식이나 최근 주행거리가 비어 있으면 첫 저장 때 요청에 있어야 한다 (D-58). 둘 다 비었으면 둘 다 알린다. */
    private static void requireVehicleInfo(VehicleRef vehicle, Integer modelYear, Integer mileage) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (vehicle.modelYear() == null && modelYear == null) {
            errors.add(new FieldErrorDetail("vehicle.modelYear", "차량의 연식이 비어 있어 입력해 주세요."));
        }
        if (vehicle.latestMileage() == null && mileage == null) {
            errors.add(new FieldErrorDetail("mileage", "차량의 주행거리가 비어 있어 입력해 주세요."));
        }
        throwIfAny(errors);
    }

    private static void throwIfAny(List<FieldErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, errors, null, null);
        }
    }

    private static String blankToNull(String memo) {
        return memo == null || memo.isBlank() ? null : memo.strip();
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
        // LIMIT: listVisits와 같은 가정(차량당 기록 수십~수백 건 이하). 넘으면 visit_key 집계 쿼리로 바꾼다.
        List<VisitGroup> visits = visitsNewestFirst(records.findByShopIdAndVehicleId(shopId, vehicleId));
        return toRefs(shopId, visits.subList(0, Math.min(limit, visits.size())));
    }

    @Override
    public long countVisits(long shopId, long vehicleId) {
        // 외부 기록만 있는 방문은 세지 않는다 (05 POL-45)
        // LIMIT: listVisits와 같은 가정(차량당 기록 수십~수백 건 이하). 넘으면 visit_key 집계 쿼리로 바꾼다.
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
        Map<Long, String> staffNames = staff.findNames(shopId, staffIds);

        return visits.stream()
            .map(v -> new VisitRef(v.visitKey(), v.visitDate(), v.mileage(),
                // 근거: staff_id는 NOT NULL FK라 항상 존재한다. 08 11의 staffName은 필수 문자열이라 방어용 "".
                staffNames.getOrDefault(v.staffId(), ""),
                v.rows().stream()
                    .map(r -> new VisitRef.VisitRecord(r.getId(), r.getServiceItemId(),
                        // 근거: (shop_id, service_item_id) 복합 FK로 항목은 항상 존재한다. ""는 방어용이다.
                        itemRefs.containsKey(r.getServiceItemId()) ? itemRefs.get(r.getServiceItemId()).name() : "",
                        r.getRecordType().name(), r.getRecordDate(), r.getMemo()))
                    .toList()))
            .toList();
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
