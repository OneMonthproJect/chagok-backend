package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.dto.RecordEditRequest;
import com.wanted.chagok.domain.maintenance.dto.RecordEditResponse;
import com.wanted.chagok.domain.maintenance.dto.RecordRevisionListResponse;
import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecord;
import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecordRevision;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.repository.MaintenanceRecordRepository;
import com.wanted.chagok.domain.maintenance.repository.MaintenanceRecordRevisionRepository;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.StaffRole;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * API 37·38 단위 테스트 (04 H1~H11, X14) — Repository·다른 도메인 계약은 가짜(DB 없음). 검증 순서·권한·기간·이력 내용을 본다.
 * 실제 {@code version} 증가·복합 FK·정비소 격리 쿼리는 DB가 필요해 {@code MaintenanceEditDbTest}(dbTest)에서 본다.
 * 오늘은 2026-11-25(Asia/Seoul)로 고정.
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceEditServiceTest {

    private static final long SHOP = 7L;
    private static final long VEHICLE = 3L;
    private static final long OWNER_ID = 1L;
    private static final long MECHANIC_ID = 11L;
    private static final long OTHER_MECHANIC_ID = 12L;
    private static final long RECORD_ID = 16L;
    private static final LocalDate TODAY = LocalDate.parse("2026-11-25");

    private static final LoginStaff OWNER = new LoginStaff(OWNER_ID, SHOP, StaffRole.OWNER);
    private static final LoginStaff MECHANIC = new LoginStaff(MECHANIC_ID, SHOP, StaffRole.MECHANIC);

    @Mock
    private MaintenanceRecordRepository repository;

    @Mock
    private MaintenanceRecordRevisionRepository revisions;

    @Mock
    private CustomerReader customers;

    @Mock
    private CustomerWriter vehicleWriter;

    @Mock
    private ItemReader items;

    @Mock
    private StaffReader staff;

    private MaintenanceService service() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        return new MaintenanceService(repository, revisions, customers, vehicleWriter, items, staff, clock);
    }

    /** 담당 {@code MECHANIC_ID}가 저장한 기록 16. 항목 1(엔진오일), 방문 키는 고정. */
    private static MaintenanceRecord record(RecordType type, LocalDate date, Integer mileage, String inspectResult,
                                            String memo, LocalDate savedOn) {
        MaintenanceRecord r = MaintenanceRecord.record(SHOP, VEHICLE, 1L, "5eed0000-0000-4000-8000-000000000015", date,
            mileage, type, MECHANIC_ID, memo, inspectResult);
        ReflectionTestUtils.setField(r, "id", RECORD_ID);
        ReflectionTestUtils.setField(r, "createdAt", savedOn.atTime(9, 0));
        ReflectionTestUtils.setField(r, "version", 0);
        return r;
    }

    private static MaintenanceRecord replaceRecord() {
        return record(RecordType.REPLACE, TODAY.minusDays(1), null, null, null, TODAY.minusDays(1));
    }

    private void stubFind(MaintenanceRecord r) {
        when(repository.findByShopIdAndId(SHOP, RECORD_ID)).thenReturn(Optional.of(r));
    }

    private static RecordEditRequest memoOnly(int version, String memo) {
        return new RecordEditRequest(version, null, null, null, null, null, memo, null, null);
    }

    private static BusinessException thrown(Runnable call) {
        return (BusinessException) assertThatThrownBy(call::run).isInstanceOf(BusinessException.class).actual();
    }

    private static void assertCode(BusinessException e, ErrorCode code) {
        assertThat(e.code()).isEqualTo(code);
    }

    // ---------- API 37 ----------

    @Test
    @DisplayName("H1: 메모 수정 — 기록이 바뀌고, 수정 전 값·수정자·사유가 이력 1건으로 남는다")
    void edit_memo_savesRevisionWithPreviousValues() {
        MaintenanceRecord r = replaceRecord();
        stubFind(r);

        RecordEditResponse res = service().editRecord(OWNER, RECORD_ID,
            new RecordEditRequest(0, null, null, null, null, null, "합성유 사용", null, "  입력 누락 보완 "));

        assertThat(res.recordId()).isEqualTo(RECORD_ID);
        assertThat(res.vehicleId()).isEqualTo(VEHICLE);
        assertThat(r.getMemo()).isEqualTo("합성유 사용");
        ArgumentCaptor<MaintenanceRecordRevision> captor = ArgumentCaptor.forClass(MaintenanceRecordRevision.class);
        verify(revisions).save(captor.capture());
        MaintenanceRecordRevision saved = captor.getValue();
        assertThat(saved.getRevisionNo()).isEqualTo(1);
        assertThat(saved.getChangedByStaffId()).isEqualTo(OWNER_ID);
        assertThat(saved.getReason()).isEqualTo("입력 누락 보완");
        assertThat(saved.getPrevMemo()).isNull();
        assertThat(saved.getPrevRecordDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(saved.getPrevStaffId()).isEqualTo(MECHANIC_ID);
        verify(repository).saveAndFlush(r);
    }

    @Test
    @DisplayName("H1: 두 번째 수정의 이력 번호는 2")
    void edit_secondRevisionNumberIsTwo() {
        MaintenanceRecord r = replaceRecord();
        ReflectionTestUtils.setField(r, "version", 1);
        stubFind(r);
        when(revisions.countByShopIdAndRecordId(SHOP, RECORD_ID)).thenReturn(1L);

        service().editRecord(OWNER, RECORD_ID, memoOnly(1, "다시 수정"));

        ArgumentCaptor<MaintenanceRecordRevision> captor = ArgumentCaptor.forClass(MaintenanceRecordRevision.class);
        verify(revisions).save(captor.capture());
        assertThat(captor.getValue().getRevisionNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("메모를 빈 문자열로 보내면 메모를 비운다")
    void edit_emptyMemoClearsMemo() {
        MaintenanceRecord r = record(RecordType.REPLACE, TODAY.minusDays(1), null, null, "지울 메모", TODAY.minusDays(1));
        stubFind(r);

        service().editRecord(OWNER, RECORD_ID, memoOnly(0, ""));

        assertThat(r.getMemo()).isNull();
    }

    @Test
    @DisplayName("H4: version이 다르면 409 CONCURRENT_MODIFICATION이고 이력·저장이 없다")
    void edit_versionMismatch_conflict() {
        stubFind(replaceRecord());

        assertCode(thrown(() -> service().editRecord(OWNER, RECORD_ID, memoOnly(5, "x"))), ErrorCode.CONCURRENT_MODIFICATION);

        verifyNoInteractions(revisions);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("H5·X14: 없거나 남의 정비소 기록은 404")
    void edit_notFound() {
        when(repository.findByShopIdAndId(SHOP, RECORD_ID)).thenReturn(Optional.empty());

        assertCode(thrown(() -> service().editRecord(OWNER, RECORD_ID, memoOnly(0, "x"))), ErrorCode.NOT_FOUND);

        verifyNoInteractions(revisions);
    }

    @Test
    @DisplayName("H6: 미래 방문일은 400 INVALID_INPUT(recordDate)이고 기록을 읽기 전에 막는다")
    void edit_futureDate_invalid() {
        RecordEditRequest req = new RecordEditRequest(0, TODAY.plusDays(1), null, null, null, null, null, null, null);

        BusinessException e = thrown(() -> service().editRecord(OWNER, RECORD_ID, req));

        assertCode(e, ErrorCode.INVALID_INPUT);
        assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("recordDate");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("주행거리가 범위(0~2,000,000)를 벗어나면 400 INVALID_INPUT(mileage)")
    void edit_mileageOutOfRange_invalid() {
        RecordEditRequest req = new RecordEditRequest(0, null, 2_000_001, null, null, null, null, null, null);

        BusinessException e = thrown(() -> service().editRecord(OWNER, RECORD_ID, req));

        assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("mileage");
    }

    @Test
    @DisplayName("H7: 수정 결과 같은 방문에 같은 항목이 둘이 되면 400 INVALID_INPUT(itemId)")
    void edit_duplicateItemInVisit_invalid() {
        MaintenanceRecord r = replaceRecord();
        stubFind(r);
        MaintenanceRecord sibling = MaintenanceRecord.record(SHOP, VEHICLE, 2L, "5eed0000-0000-4000-8000-000000000015",
            TODAY.minusDays(1), null, RecordType.INSPECT, MECHANIC_ID, null, null);
        ReflectionTestUtils.setField(sibling, "id", 17L);
        when(repository.findByShopIdAndVisitKeyOrderByIdAsc(SHOP, "5eed0000-0000-4000-8000-000000000015"))
            .thenReturn(List.of(r, sibling));

        BusinessException e = thrown(() -> service().editRecord(OWNER, RECORD_ID,
            new RecordEditRequest(0, null, null, null, 2L, null, null, null, null)));

        assertCode(e, ErrorCode.INVALID_INPUT);
        assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("itemId");
        verifyNoInteractions(revisions);
    }

    @Test
    @DisplayName("X11: 쓰지 않는 항목으로 바꾸면 항목 검사에서 400 (그 도메인 한 곳)")
    void edit_itemChange_checksActiveItem() {
        MaintenanceRecord r = replaceRecord();
        stubFind(r);

        service().editRecord(OWNER, RECORD_ID, new RecordEditRequest(0, null, null, null, 2L, null, null, null, null));

        verify(items).requireActiveItems(SHOP, List.of(2L), "itemId");
        assertThat(r.getServiceItemId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("H8: 바뀐 필드가 없으면(version만, 또는 지금과 같은 값) 400이고 이력이 없다")
    void edit_noChange_invalid() {
        stubFind(record(RecordType.REPLACE, TODAY.minusDays(1), 50_000, null, "그대로", TODAY.minusDays(1)));

        BusinessException onlyVersion = thrown(() -> service().editRecord(OWNER, RECORD_ID, memoOnly(0, null)));
        BusinessException same = thrown(() -> service().editRecord(OWNER, RECORD_ID,
            new RecordEditRequest(0, null, 50_000, null, null, null, "그대로", null, null)));

        assertThat(onlyVersion.fieldErrors()).extracting(f -> f.field()).containsExactly("recordDate");
        assertThat(same.fieldErrors()).extracting(f -> f.field()).containsExactly("mileage");
        verifyNoInteractions(revisions);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("H3: 정비사가 남이 담당한 기록을 고치면 403 FORBIDDEN")
    void edit_notAssignee_forbidden() {
        stubFind(replaceRecord());

        BusinessException e = thrown(() -> service().editRecord(new LoginStaff(OTHER_MECHANIC_ID, SHOP, StaffRole.MECHANIC),
            RECORD_ID, memoOnly(0, "x")));

        assertCode(e, ErrorCode.FORBIDDEN);
        verifyNoInteractions(revisions);
    }

    @Test
    @DisplayName("H10: 정비사는 방문일 + 3일 이내(3일째)까지 가능하고 4일째는 403 EDIT_PERIOD_EXPIRED")
    void edit_mechanicPeriodBoundary() {
        MaintenanceRecord day3 = record(RecordType.REPLACE, TODAY.minusDays(3), null, null, null, TODAY.minusDays(3));
        stubFind(day3);
        service().editRecord(MECHANIC, RECORD_ID, memoOnly(0, "3일째"));
        assertThat(day3.getMemo()).isEqualTo("3일째");

        MaintenanceRecord day4 = record(RecordType.REPLACE, TODAY.minusDays(4), null, null, null, TODAY.minusDays(1));
        stubFind(day4);
        assertCode(thrown(() -> service().editRecord(MECHANIC, RECORD_ID, memoOnly(0, "4일째"))), ErrorCode.EDIT_PERIOD_EXPIRED);
    }

    @Test
    @DisplayName("H10: 외부 기록은 방문일(고객이 말한 정비일)이 아니라 저장일로 기간을 센다")
    void edit_externalRecord_countsFromSavedDay() {
        // 정비일은 한 달 전이지만 어제 저장했다 → 정비사도 수정할 수 있다
        MaintenanceRecord r = record(RecordType.EXTERNAL, TODAY.minusDays(30), null, null, null, TODAY.minusDays(1));
        stubFind(r);

        service().editRecord(MECHANIC, RECORD_ID, memoOnly(0, "고객 진술 보완"));

        assertThat(r.getMemo()).isEqualTo("고객 진술 보완");
    }

    @Test
    @DisplayName("H11: 사장은 4일 넘게 지난 기록도 수정한다")
    void edit_ownerNoPeriodLimit() {
        MaintenanceRecord r = record(RecordType.REPLACE, TODAY.minusDays(90), null, null, null, TODAY.minusDays(90));
        stubFind(r);

        service().editRecord(OWNER, RECORD_ID, memoOnly(0, "오래된 기록 보완"));

        assertThat(r.getMemo()).isEqualTo("오래된 기록 보완");
    }

    @Test
    @DisplayName("담당 정비사를 바꾸면 그 정비소 직원인지 확인하고 새 담당이 저장된다 (X13)")
    void edit_changeMechanic() {
        MaintenanceRecord r = replaceRecord();
        stubFind(r);
        when(staff.findNames(SHOP, List.of(OTHER_MECHANIC_ID))).thenReturn(Map.of(OTHER_MECHANIC_ID, "이정비"));

        service().editRecord(OWNER, RECORD_ID,
            new RecordEditRequest(0, null, null, null, null, null, null, OTHER_MECHANIC_ID, null));

        assertThat(r.getStaffId()).isEqualTo(OTHER_MECHANIC_ID);
    }

    @Test
    @DisplayName("X13: 그 정비소 직원이 아닌 담당으로 바꾸면 400 INVALID_INPUT(mechanicId)")
    void edit_unknownMechanic_invalid() {
        stubFind(replaceRecord());
        when(staff.findNames(SHOP, List.of(99L))).thenReturn(Map.of());

        BusinessException e = thrown(() -> service().editRecord(OWNER, RECORD_ID,
            new RecordEditRequest(0, null, null, null, null, null, null, 99L, null)));

        assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("mechanicId");
        verifyNoInteractions(revisions);
    }

    @Test
    @DisplayName("교체 → 점검으로 구분을 바꾸고 점검 결과를 함께 보낼 수 있다. 결과를 안 보내면 null")
    void edit_typeToInspect() {
        MaintenanceRecord withResult = replaceRecord();
        stubFind(withResult);
        service().editRecord(OWNER, RECORD_ID,
            new RecordEditRequest(0, null, null, RecordType.INSPECT, null, "CAUTION", null, null, null));
        assertThat(withResult.getRecordType()).isEqualTo(RecordType.INSPECT);
        assertThat(withResult.getInspectResult()).isEqualTo("CAUTION");

        MaintenanceRecord withoutResult = replaceRecord();
        stubFind(withoutResult);
        service().editRecord(OWNER, RECORD_ID,
            new RecordEditRequest(0, null, null, RecordType.INSPECT, null, null, null, null, null));
        assertThat(withoutResult.getInspectResult()).isNull();
    }

    @Test
    @DisplayName("점검 → 다른 구분으로 바꾸면 기존 점검 결과가 비워지고, 외부로 바꾸면 출처가 고객 진술로 맞춰진다")
    void edit_typeAwayFromInspect_clearsResult_andSyncsSource() {
        MaintenanceRecord r = record(RecordType.INSPECT, TODAY.minusDays(1), null, "GOOD", null, TODAY.minusDays(1));
        stubFind(r);

        service().editRecord(OWNER, RECORD_ID, new RecordEditRequest(0, null, null, RecordType.EXTERNAL, null, null, null, null, null));

        assertThat(r.getInspectResult()).isNull();
        assertThat(r.getSource()).isEqualTo("CUSTOMER_STATEMENT");
    }

    @Test
    @DisplayName("수정 뒤 구분이 점검이 아닌데 점검 결과를 보내면 400 INVALID_INPUT(inspectResult)")
    void edit_inspectResultWithNonInspect_invalid() {
        stubFind(replaceRecord());

        BusinessException e = thrown(() -> service().editRecord(OWNER, RECORD_ID,
            new RecordEditRequest(0, null, null, null, null, "GOOD", null, null, null)));

        assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("inspectResult");
    }

    @Test
    @DisplayName("POL-60: 주행거리를 바꾸면 차량 갱신 계약을 호출한다(크기 비교는 그 한 곳). 바꾸지 않으면 호출하지 않는다")
    void edit_mileage_appliesToVehicle() {
        MaintenanceRecord r = replaceRecord();
        stubFind(r);

        service().editRecord(OWNER, RECORD_ID, new RecordEditRequest(0, null, 52_000, null, null, null, null, null, null));
        verify(vehicleWriter).applyVisit(SHOP, VEHICLE, null, 52_000);

        MaintenanceRecord memoEdit = replaceRecord();
        stubFind(memoEdit);
        service().editRecord(OWNER, RECORD_ID, memoOnly(0, "메모만"));
        verify(vehicleWriter, org.mockito.Mockito.times(1)).applyVisit(anyLong(), anyLong(), any(), any());
    }

    // ---------- API 38 ----------

    private static MaintenanceRecordRevision revision(MaintenanceRecord before, int no, long changedBy, String reason,
                                                      String changedAt) {
        MaintenanceRecordRevision rev = MaintenanceRecordRevision.before(before, no, changedBy, reason);
        ReflectionTestUtils.setField(rev, "createdAt", LocalDateTime.parse(changedAt));
        return rev;
    }

    @Test
    @DisplayName("38: 한 수정의 changes는 수정 전 값과 다음 수정의 수정 전 값(마지막은 지금 기록)을 비교해 순서대로 만든다")
    void revisions_buildChangesFromSnapshots() {
        // 기록 16: 처음 REPLACE/주행거리 없음/메모 없음 → (1) 주행거리·메모 보완 → (2) 점검(주의)으로 정정
        MaintenanceRecord initial = replaceRecord();
        MaintenanceRecordRevision rev1 = revision(initial, 1, OWNER_ID, "입력 누락 보완", "2026-11-25T10:05:00");
        MaintenanceRecord afterFirst = record(RecordType.REPLACE, TODAY.minusDays(1), 52_000, null, "합성유 사용", TODAY.minusDays(1));
        MaintenanceRecordRevision rev2 = revision(afterFirst, 2, OWNER_ID, null, "2026-11-26T09:30:00");
        MaintenanceRecord current = record(RecordType.INSPECT, TODAY.minusDays(1), 52_000, "CAUTION", "합성유 사용", TODAY.minusDays(1));

        when(repository.findByShopIdAndId(SHOP, RECORD_ID)).thenReturn(Optional.of(current));
        when(revisions.findByShopIdAndRecordIdOrderByRevisionNoAsc(SHOP, RECORD_ID)).thenReturn(List.of(rev1, rev2));
        when(items.findItems(anyLong(), any())).thenReturn(Map.of(1L, new ItemRef(1L, "ENGINE_OIL", "엔진오일", 6, 10_000, true, 1)));
        when(staff.findNames(anyLong(), any())).thenReturn(Map.of(OWNER_ID, "박사장", MECHANIC_ID, "김정비"));

        RecordRevisionListResponse res = service().revisions(SHOP, RECORD_ID);

        assertThat(res.items()).hasSize(2);
        RecordRevisionListResponse.Item first = res.items().get(0);
        assertThat(first.revisionNo()).isEqualTo(1);
        assertThat(first.changedAt()).isEqualTo(LocalDateTime.parse("2026-11-25T10:05:00"));
        assertThat(first.changedBy()).isEqualTo(new RecordRevisionListResponse.Person(OWNER_ID, "박사장"));
        assertThat(first.reason()).isEqualTo("입력 누락 보완");
        assertThat(first.changes()).containsExactly(
            new RecordRevisionListResponse.Change("mileage", null, 52_000),
            new RecordRevisionListResponse.Change("memo", null, "합성유 사용"));

        RecordRevisionListResponse.Item second = res.items().get(1);
        assertThat(second.revisionNo()).isEqualTo(2);
        assertThat(second.reason()).isNull();
        // 구분을 바꿔 점검 결과가 채워진 것도 inspectResult 항목으로 나온다
        assertThat(second.changes()).containsExactly(
            new RecordRevisionListResponse.Change("type", "REPLACE", "INSPECT"),
            new RecordRevisionListResponse.Change("inspectResult", null, "CAUTION"));
    }

    @Test
    @DisplayName("38: 항목·담당이 바뀐 수정은 item·mechanic 항목에 {id, name}으로 나온다")
    void revisions_itemAndMechanicChanges() {
        MaintenanceRecord initial = replaceRecord();
        MaintenanceRecordRevision rev1 = revision(initial, 1, OWNER_ID, null, "2026-11-25T10:05:00");
        MaintenanceRecord current = record(RecordType.REPLACE, TODAY.minusDays(1), null, null, null, TODAY.minusDays(1));
        ReflectionTestUtils.setField(current, "serviceItemId", 2L);
        ReflectionTestUtils.setField(current, "staffId", OTHER_MECHANIC_ID);

        when(repository.findByShopIdAndId(SHOP, RECORD_ID)).thenReturn(Optional.of(current));
        when(revisions.findByShopIdAndRecordIdOrderByRevisionNoAsc(SHOP, RECORD_ID)).thenReturn(List.of(rev1));
        when(items.findItems(anyLong(), any())).thenReturn(Map.of(
            1L, new ItemRef(1L, "ENGINE_OIL", "엔진오일", 6, 10_000, true, 1),
            2L, new ItemRef(2L, "AIR_FILTER", "에어클리너", 12, 15_000, true, 2)));
        when(staff.findNames(anyLong(), any())).thenReturn(Map.of(OWNER_ID, "박사장", MECHANIC_ID, "김정비", OTHER_MECHANIC_ID, "이정비"));

        RecordRevisionListResponse res = service().revisions(SHOP, RECORD_ID);

        assertThat(res.items().get(0).changes()).containsExactly(
            new RecordRevisionListResponse.Change("item", new RecordRevisionListResponse.ItemValue(1L, "엔진오일"),
                new RecordRevisionListResponse.ItemValue(2L, "에어클리너")),
            new RecordRevisionListResponse.Change("mechanic", new RecordRevisionListResponse.Person(MECHANIC_ID, "김정비"),
                new RecordRevisionListResponse.Person(OTHER_MECHANIC_ID, "이정비")));
    }

    @Test
    @DisplayName("38: 수정한 적이 없으면 items는 빈 목록이다")
    void revisions_empty() {
        when(repository.findByShopIdAndId(SHOP, RECORD_ID)).thenReturn(Optional.of(replaceRecord()));
        when(revisions.findByShopIdAndRecordIdOrderByRevisionNoAsc(SHOP, RECORD_ID)).thenReturn(List.of());

        assertThat(service().revisions(SHOP, RECORD_ID).items()).isEmpty();
    }

    @Test
    @DisplayName("H5·X14: 38도 없거나 남의 정비소 기록이면 404")
    void revisions_notFound() {
        when(repository.findByShopIdAndId(SHOP, RECORD_ID)).thenReturn(Optional.empty());

        assertCode(thrown(() -> service().revisions(SHOP, RECORD_ID)), ErrorCode.NOT_FOUND);
    }
}
