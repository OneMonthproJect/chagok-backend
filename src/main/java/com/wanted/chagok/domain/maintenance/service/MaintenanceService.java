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
import com.wanted.chagok.domain.maintenance.dto.RecordEditRequest;
import com.wanted.chagok.domain.maintenance.dto.RecordEditResponse;
import com.wanted.chagok.domain.maintenance.dto.RecordRevisionListResponse;
import com.wanted.chagok.domain.maintenance.dto.VisitListResponse;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveResponse;
import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecord;
import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecordRevision;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.repository.MaintenanceRecordRepository;
import com.wanted.chagok.domain.maintenance.repository.MaintenanceRecordRevisionRepository;
import com.wanted.chagok.global.auth.LoginStaff;
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
 * 방문 이력 조회(API 11, F-MNT-01), 방문·정비 기록 저장(API 12), 기록 수정(API 37)·수정 이력(API 38, F-MNT-04)과 다른 도메인에 주는 읽기 계약 구현.
 * 차량 소속은 {@code customer.api}, 항목은 {@code item.api}, 담당 이름은 {@code account.api}로만 확인한다. 차량 값 반영도 {@code customer.api}의
 * 쓰기 계약({@link CustomerWriter})으로만 한다. 모든 조회는 {@code shopId}와 함께 한다(I-3).
 */
@Service
@Transactional(readOnly = true)
public class MaintenanceService implements MaintenanceReader {

    private static final String REQUIRED = "필수 입력입니다.";
    private static final String EXTERNAL_ONLY = "외부 기록일 때만 입력할 수 있습니다.";
    private static final int MAX_MILEAGE = 2_000_000;
    private static final Set<String> INSPECT_RESULTS = Set.of("GOOD", "CAUTION");

    private final MaintenanceRecordRepository records;
    private final MaintenanceRecordRevisionRepository revisions;
    private final CustomerReader customers;
    private final CustomerWriter vehicleWriter;
    private final ItemReader items;
    private final StaffReader staff;
    private final Clock clock;

    public MaintenanceService(MaintenanceRecordRepository records, MaintenanceRecordRevisionRepository revisions,
                              CustomerReader customers, CustomerWriter vehicleWriter, ItemReader items,
                              StaffReader staff, Clock clock) {
        this.records = records;
        this.revisions = revisions;
        this.customers = customers;
        this.vehicleWriter = vehicleWriter;
        this.items = items;
        this.staff = staff;
        this.clock = clock;
    }

    /**
     * API 12. 한 방문의 기록 행들을 한 {@code visit_key}로 저장하고, 같은 트랜잭션에서 차량의 연식·최근 주행거리를 반영한다
     * (하나라도 실패하면 아무것도 저장하지 않는다). 담당은 요청의 {@code mechanicId}(그 정비소 직원, X13)이고 없으면 저장한 계정이다.
     * 검증 순서는 차량 소속(404, X5) → 방문일 → 구분별 입력(외부 정비일·주행거리, 점검 결과) → 항목 중복 → 쓰는 항목(X11) →
     * 담당(X13) → 교체·점검이 있을 때 차량이 비어 있으면 필수(D-58) → 저장.
     * 외부 기록은 {@code externalDate}·{@code externalMileage}를 기록일·주행거리로 저장한다(주기 재시작, T13).
     */
    @Transactional
    public VisitSaveResponse saveVisit(long shopId, long staffId, long vehicleId, VisitSaveRequest request) {
        VehicleRef vehicle = vehicleWriter.getOwnedVehicleForVisit(shopId, vehicleId);

        LocalDate today = LocalDate.now(clock);
        if (request.visitDate().isAfter(today)) {
            throw BusinessException.invalidField("visitDate", "오늘 이전 날짜만 입력할 수 있습니다.");
        }
        validateRecordInputs(request.records(), today);
        requireNoDuplicateItems(request.records());
        items.requireActiveItems(shopId,
            request.records().stream().map(VisitSaveRequest.RecordInput::itemId).toList(), "records[].itemId");
        long mechanicId = resolveMechanic(shopId, staffId, request.mechanicId());

        Integer modelYear = request.vehicle() == null ? null : request.vehicle().modelYear();
        // 외부·거절만 있는 방문은 차량 정보를 강제하지 않는다 (08 12: 교체·점검 항목이 있을 때만 필수)
        if (hasShopWork(request.records())) {
            CustomerWriter.requireVehicleInfo(vehicle, modelYear, request.mileage(), "vehicle.modelYear");
        }
        // 연식·주행거리의 범위 검증과 반영 규칙은 customer 한 곳에 있다. 실패하면 이 트랜잭션이 통째로 취소된다.
        vehicleWriter.applyVisit(shopId, vehicleId, modelYear, request.mileage());

        String visitKey = UUID.randomUUID().toString();
        List<MaintenanceRecord> rows = request.records().stream()
            .map(r -> r.type() == RecordType.EXTERNAL
                ? MaintenanceRecord.record(shopId, vehicleId, r.itemId(), visitKey, r.externalDate(),
                    r.externalMileage(), r.type(), mechanicId, blankToNull(r.memo()), null)
                : MaintenanceRecord.record(shopId, vehicleId, r.itemId(), visitKey, request.visitDate(),
                    request.mileage(), r.type(), mechanicId, blankToNull(r.memo()), r.inspectResult()))
            .toList();
        return new VisitSaveResponse(vehicleId, visitKey, records.saveAll(rows).size());
    }

    /**
     * 구분별 입력 검사 (08 12). 외부는 정비일 필수(오늘 이전)·주행거리 0~2,000,000, 점검 결과는 점검일 때만 GOOD·CAUTION.
     * 외부 값은 외부 구분일 때만 받는다. 틀린 필드는 모두 모아 한 번에 알린다.
     */
    private static void validateRecordInputs(List<VisitSaveRequest.RecordInput> rows, LocalDate today) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            VisitSaveRequest.RecordInput r = rows.get(i);
            String at = "records[" + i + "].";
            if (r.type() == RecordType.EXTERNAL) {
                if (r.externalDate() == null) {
                    errors.add(new FieldErrorDetail(at + "externalDate", REQUIRED));
                } else if (r.externalDate().isAfter(today)) {
                    errors.add(new FieldErrorDetail(at + "externalDate", "오늘 이전 날짜만 입력할 수 있습니다."));
                }
                if (r.externalMileage() != null && (r.externalMileage() < 0 || r.externalMileage() > MAX_MILEAGE)) {
                    errors.add(new FieldErrorDetail(at + "externalMileage", "0 ~ 2,000,000 사이로 입력해 주세요."));
                }
            } else {
                if (r.externalDate() != null) {
                    errors.add(new FieldErrorDetail(at + "externalDate", EXTERNAL_ONLY));
                }
                if (r.externalMileage() != null) {
                    errors.add(new FieldErrorDetail(at + "externalMileage", EXTERNAL_ONLY));
                }
            }
            if (r.inspectResult() != null) {
                if (r.type() != RecordType.INSPECT) {
                    errors.add(new FieldErrorDetail(at + "inspectResult", "점검일 때만 입력할 수 있습니다."));
                } else if (!INSPECT_RESULTS.contains(r.inspectResult())) {
                    errors.add(new FieldErrorDetail(at + "inspectResult", "GOOD 또는 CAUTION만 입력할 수 있습니다."));
                }
            }
        }
        throwIfAny(errors);
    }

    /** 담당 정비사: 요청에 없으면 저장한 계정, 있으면 그 정비소 직원이어야 한다. 없음·남의 것은 같은 400 (X13). */
    private long resolveMechanic(long shopId, long staffId, Long mechanicId) {
        if (mechanicId == null) {
            return staffId;
        }
        // 근거: staff.role은 OWNER·MECHANIC 둘뿐이라(07 3.3) "그 정비소 직원"이 곧 사장·정비사다. 활성 판정은 P1(V3)부터 (08 0.1).
        if (!staff.findNames(shopId, List.of(mechanicId)).containsKey(mechanicId)) {
            throw BusinessException.invalidField("mechanicId", "담당 정비사를 확인해 주세요.");
        }
        return mechanicId;
    }

    /** 교체·점검이 하나라도 있으면 true. 외부·거절만 있는 방문이면 false. */
    private static boolean hasShopWork(List<VisitSaveRequest.RecordInput> rows) {
        return rows.stream().anyMatch(r -> r.type() == RecordType.REPLACE || r.type() == RecordType.INSPECT);
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

    private static void throwIfAny(List<FieldErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, errors, null, null);
        }
    }

    private static String blankToNull(String memo) {
        return memo == null || memo.isBlank() ? null : memo.strip();
    }

    /**
     * API 37 정비 기록 수정 (04 F-MNT-04, D-55). 순서(08 37번): 형식(400) → 정비소 소유(404, X14) → {@code version}(409) →
     * 권한({@link MaintenanceEditRule}, 403) → 항목·담당(400, X11·X13) → 구분·점검 결과 짝(400) → 바뀐 필드가 있는지(400) →
     * 같은 방문 항목 중복(400) → 저장. 하나라도 어긋나면 기록·{@code version}·이력이 모두 그대로다.
     * 수정 전 값 전체를 이력 1행으로 같은 트랜잭션에 남기고, 바뀐 주행거리가 차량의 최근 주행거리보다 크면 차량을 갱신한다(POL-60).
     */
    @Transactional
    public RecordEditResponse editRecord(LoginStaff staff, long recordId, RecordEditRequest request) {
        long shopId = staff.shopId();
        LocalDate today = LocalDate.now(clock);
        validateEditFormat(request, today);

        MaintenanceRecord record = records.findByShopIdAndId(shopId, recordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        record.requireVersion(request.version());
        MaintenanceEditRule.requireAllowed(staff.isOwner(), staff.staffId() == record.getStaffId(), today,
            record.editPeriodBaseDate());

        if (request.itemId() != null && request.itemId() != record.getServiceItemId()) {
            items.requireActiveItems(shopId, List.of(request.itemId()), "itemId");
        }
        long newStaffId = record.getStaffId();
        if (request.mechanicId() != null && request.mechanicId() != record.getStaffId()) {
            newStaffId = resolveMechanic(shopId, staff.staffId(), request.mechanicId());
        }

        // 구분을 바꾸면 점검 결과는 비운다. INSPECT로 바꾸면서 결과를 안 보내면 null (08 37번)
        RecordType newType = request.type() != null ? request.type() : record.getRecordType();
        if (request.inspectResult() != null && newType != RecordType.INSPECT) {
            throw BusinessException.invalidField("inspectResult", "점검일 때만 입력할 수 있습니다.");
        }
        String newInspect = newType != RecordType.INSPECT ? null
            : request.inspectResult() != null ? request.inspectResult()
            : newType == record.getRecordType() ? record.getInspectResult() : null;
        LocalDate newDate = request.recordDate() != null ? request.recordDate() : record.getRecordDate();
        Integer newMileage = request.mileage() != null ? request.mileage() : record.getMileage();
        long newItemId = request.itemId() != null ? request.itemId() : record.getServiceItemId();
        String newMemo = request.memo() != null ? blankToNull(request.memo()) : record.getMemo();

        // 지금 값과 같은 필드는 바꾸는 것이 아니다 (08 37번)
        boolean mileageChanges = !Objects.equals(newMileage, record.getMileage());
        boolean changes = !newDate.equals(record.getRecordDate()) || mileageChanges || newType != record.getRecordType()
            || newItemId != record.getServiceItemId() || !Objects.equals(newInspect, record.getInspectResult())
            || !Objects.equals(newMemo, record.getMemo()) || newStaffId != record.getStaffId();
        if (!changes) {
            throw BusinessException.invalidField(firstSentField(request), "바뀐 항목이 없습니다.");
        }
        // 같은 방문의 행을 잠근 채로 읽어, 동시에 같은 항목으로 바꾸는 다른 수정과 겹치지 않게 한다 (H7)
        if (newItemId != record.getServiceItemId() && records.findByShopIdAndVisitKeyOrderByIdAsc(shopId, record.getVisitKey())
            .stream().anyMatch(o -> !o.getId().equals(record.getId()) && o.getServiceItemId() == newItemId)) {
            throw BusinessException.invalidField("itemId", "같은 방문에 같은 항목을 두 번 넣을 수 없습니다.");
        }

        long revisionNo = revisions.countByShopIdAndRecordId(shopId, record.getId()) + 1;
        // 수정 전 값은 지금 만들어 두고, 기록을 먼저 저장한다: 동시에 고친 요청은 여기서 version 충돌(409)로 막히고,
        // 이력 번호 UNIQUE 충돌(500)까지 가지 않는다.
        MaintenanceRecordRevision revision = MaintenanceRecordRevision.before(record, (int) revisionNo, staff.staffId(),
            request.reason() == null || request.reason().isBlank() ? null : request.reason().strip());
        record.edit(newDate, newMileage, newType, newItemId, newInspect, newMemo, newStaffId);
        records.saveAndFlush(record);
        revisions.save(revision);
        if (mileageChanges && newMileage != null) {
            // 차량의 최근 주행거리보다 클 때만 갱신, 작거나 같으면 그대로(오류 아님, POL-60)
            vehicleWriter.applyVisit(shopId, record.getVehicleId(), null, newMileage);
        }
        return new RecordEditResponse(record.getId(), record.getVehicleId(), record.getVersion());
    }

    /** 보낸 필드의 형식·범위 (400). 미래 방문일, 주행거리 0~2,000,000, 점검 결과 값. 틀린 필드는 모아서 알린다. */
    private void validateEditFormat(RecordEditRequest request, LocalDate today) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (request.recordDate() != null && request.recordDate().isAfter(today)) {
            errors.add(new FieldErrorDetail("recordDate", "오늘 이전 날짜만 입력할 수 있습니다."));
        }
        if (request.mileage() != null && (request.mileage() < 0 || request.mileage() > MAX_MILEAGE)) {
            errors.add(new FieldErrorDetail("mileage", "0 ~ 2,000,000 사이로 입력해 주세요."));
        }
        if (request.inspectResult() != null && !INSPECT_RESULTS.contains(request.inspectResult())) {
            errors.add(new FieldErrorDetail("inspectResult", "GOOD 또는 CAUTION만 입력할 수 있습니다."));
        }
        throwIfAny(errors);
    }

    /** "바뀐 것 없음" 오류의 필드: 보낸 필드 중 요청 표 순서로 첫째, 하나도 없으면 recordDate (08 37번). */
    private static String firstSentField(RecordEditRequest r) {
        if (r.recordDate() != null) {
            return "recordDate";
        }
        if (r.mileage() != null) {
            return "mileage";
        }
        if (r.type() != null) {
            return "type";
        }
        if (r.itemId() != null) {
            return "itemId";
        }
        if (r.inspectResult() != null) {
            return "inspectResult";
        }
        if (r.memo() != null) {
            return "memo";
        }
        return r.mechanicId() != null ? "mechanicId" : "recordDate";
    }

    /**
     * API 38 수정 이력 조회 (X14). 남의 정비소 기록은 404. 한 수정의 {@code changes}는 그 수정의 수정 전 값과 다음 수정의
     * 수정 전 값(마지막은 지금 기록)을 비교해 만든다. 이름은 항목·계정의 현재 이름이고 한 번에 읽는다(N+1 방지).
     */
    public RecordRevisionListResponse revisions(long shopId, long recordId) {
        MaintenanceRecord record = records.findByShopIdAndId(shopId, recordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        List<MaintenanceRecordRevision> rows = revisions.findByShopIdAndRecordIdOrderByRevisionNoAsc(shopId, recordId);
        if (rows.isEmpty()) {
            return new RecordRevisionListResponse(List.of());
        }

        List<Snapshot> snapshots = new ArrayList<>();
        rows.forEach(r -> snapshots.add(new Snapshot(r.getPrevRecordDate(), r.getPrevMileage(), r.getPrevRecordType(),
            r.getPrevServiceItemId(), r.getPrevInspectResult(), r.getPrevMemo(), r.getPrevStaffId())));
        snapshots.add(new Snapshot(record.getRecordDate(), record.getMileage(), record.getRecordType(),
            record.getServiceItemId(), record.getInspectResult(), record.getMemo(), record.getStaffId()));

        Set<Long> itemIds = snapshots.stream().map(Snapshot::itemId).collect(Collectors.toSet());
        Set<Long> staffIds = new HashSet<>();
        snapshots.forEach(sn -> staffIds.add(sn.staffId()));
        rows.forEach(r -> staffIds.add(r.getChangedByStaffId()));
        Map<Long, ItemRef> itemRefs = items.findItems(shopId, itemIds);
        Map<Long, String> names = staff.findNames(shopId, staffIds);

        List<RecordRevisionListResponse.Item> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            MaintenanceRecordRevision r = rows.get(i);
            result.add(new RecordRevisionListResponse.Item(r.getRevisionNo(), r.getCreatedAt(),
                new RecordRevisionListResponse.Person(r.getChangedByStaffId(), names.getOrDefault(r.getChangedByStaffId(), "")),
                r.getReason(), changesBetween(snapshots.get(i), snapshots.get(i + 1), itemRefs, names)));
        }
        return new RecordRevisionListResponse(result);
    }

    /** 08 38번 {@code changes[]}: 바뀐 필드마다 한 항목, recordDate → mileage → type → item → inspectResult → memo → mechanic 순서. */
    private static List<RecordRevisionListResponse.Change> changesBetween(Snapshot before, Snapshot after,
                                                                          Map<Long, ItemRef> itemRefs, Map<Long, String> names) {
        List<RecordRevisionListResponse.Change> out = new ArrayList<>();
        addIfChanged(out, "recordDate", before.recordDate(), after.recordDate());
        addIfChanged(out, "mileage", before.mileage(), after.mileage());
        addIfChanged(out, "type", before.type().name(), after.type().name());
        if (before.itemId() != after.itemId()) {
            out.add(new RecordRevisionListResponse.Change("item", itemValue(before.itemId(), itemRefs),
                itemValue(after.itemId(), itemRefs)));
        }
        addIfChanged(out, "inspectResult", before.inspectResult(), after.inspectResult());
        addIfChanged(out, "memo", before.memo(), after.memo());
        if (before.staffId() != after.staffId()) {
            out.add(new RecordRevisionListResponse.Change("mechanic",
                new RecordRevisionListResponse.Person(before.staffId(), names.getOrDefault(before.staffId(), "")),
                new RecordRevisionListResponse.Person(after.staffId(), names.getOrDefault(after.staffId(), ""))));
        }
        return out;
    }

    private static void addIfChanged(List<RecordRevisionListResponse.Change> out, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            out.add(new RecordRevisionListResponse.Change(field, before, after));
        }
    }

    private static RecordRevisionListResponse.ItemValue itemValue(long itemId, Map<Long, ItemRef> itemRefs) {
        // 근거: 이력의 항목은 복합 FK로 있던 항목이라 항상 존재한다. ""는 방어용이다.
        return new RecordRevisionListResponse.ItemValue(itemId, itemRefs.containsKey(itemId) ? itemRefs.get(itemId).name() : "");
    }

    /** 이력 비교용 기록 값 한 벌. */
    private record Snapshot(LocalDate recordDate, Integer mileage, RecordType type, long itemId, String inspectResult,
                            String memo, long staffId) {
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

    /** 방문 묶음에 항목 이름·담당 정비사 이름·수정 여부를 붙인다. 이름·수정 여부는 한 번의 조회로 가져온다(건별 호출 금지). */
    private List<VisitRef> toRefs(long shopId, List<VisitGroup> visits) {
        if (visits.isEmpty()) {
            return List.of();
        }
        Set<Long> itemIds = visits.stream()
            .flatMap(v -> v.rows().stream())
            .map(MaintenanceRecord::getServiceItemId)
            .collect(Collectors.toSet());
        Map<Long, ItemRef> itemRefs = items.findItems(shopId, itemIds);
        Set<Long> recordIds = visits.stream().flatMap(v -> v.rows().stream())
            .map(MaintenanceRecord::getId).collect(Collectors.toSet());
        // 수정 이력이 있는 기록(edited)과 그 기록의 처음 담당. 묶음의 staffName은 담당을 고쳐도 바뀌지 않게 처음 담당으로 정한다(08 11)
        Map<Long, Long> originalStaff = revisions.findOriginalStaff(shopId, recordIds).stream()
            .collect(Collectors.toMap(MaintenanceRecordRevisionRepository.OriginalStaff::getRecordId,
                MaintenanceRecordRevisionRepository.OriginalStaff::getStaffId));
        // records[].staff는 기록마다의 지금 담당이라 모든 기록의 담당, 묶음 이름은 처음 담당의 이름을 한 번에 읽는다
        Set<Long> staffIds = visits.stream().flatMap(v -> v.rows().stream())
            .map(MaintenanceRecord::getStaffId).collect(Collectors.toCollection(HashSet::new));
        staffIds.addAll(originalStaff.values());
        Map<Long, String> staffNames = staff.findNames(shopId, staffIds);

        return visits.stream()
            .map(v -> new VisitRef(v.visitKey(), v.visitDate(), v.mileage(),
                // 근거: staff_id는 NOT NULL FK라 항상 존재한다. 08 11의 staffName은 필수 문자열이라 방어용 "".
                staffNames.getOrDefault(originalStaff.getOrDefault(v.rows().get(0).getId(), v.staffId()), ""),
                v.rows().stream()
                    .map(r -> new VisitRef.VisitRecord(r.getId(), r.getServiceItemId(),
                        // 근거: (shop_id, service_item_id) 복합 FK로 항목은 항상 존재한다. ""는 방어용이다.
                        itemRefs.containsKey(r.getServiceItemId()) ? itemRefs.get(r.getServiceItemId()).name() : "",
                        r.getRecordType().name(), r.getRecordDate(), r.getMemo(),
                        r.getVersion(), originalStaff.containsKey(r.getId()),
                        new VisitRef.RecordStaff(r.getStaffId(), staffNames.getOrDefault(r.getStaffId(), ""))))
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
