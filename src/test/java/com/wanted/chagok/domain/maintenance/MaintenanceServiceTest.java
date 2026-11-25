package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.domain.maintenance.api.VisitRef;
import com.wanted.chagok.domain.maintenance.dto.VisitListResponse;
import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecord;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.repository.MaintenanceRecordRepository;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.web.PageLimit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code MaintenanceService} 단위 테스트 — Repository·다른 도메인 계약은 가짜(DB 없음). 방문 묶음·정렬·쪽 나누기·404 순서를 본다.
 * Entity ↔ V1 {@code validate}와 정비소 격리 쿼리는 DB가 필요해 {@code MaintenanceServiceDbTest}(dbTest)에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceServiceTest {

    private static final long SHOP = 7L;
    private static final long VEHICLE = 3L;

    @Mock
    private MaintenanceRecordRepository repository;

    @Mock
    private CustomerReader customers;

    @Mock
    private com.wanted.chagok.domain.customer.api.CustomerWriter vehicleWriter;

    @Mock
    private ItemReader items;

    @Mock
    private StaffReader staff;

    private MaintenanceService service() {
        return new MaintenanceService(repository, customers, vehicleWriter, items, staff,
            java.time.Clock.fixed(java.time.Instant.parse("2026-11-25T03:00:00Z"), java.time.ZoneId.of("Asia/Seoul")));
    }

    private static MaintenanceRecord record(long id, String visitKey, long itemId, String date, Integer mileage,
        RecordType type, String savedAt) {
        try {
            var ctor = MaintenanceRecord.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            MaintenanceRecord r = ctor.newInstance();
            ReflectionTestUtils.setField(r, "id", id);
            ReflectionTestUtils.setField(r, "shopId", SHOP);
            ReflectionTestUtils.setField(r, "vehicleId", VEHICLE);
            ReflectionTestUtils.setField(r, "serviceItemId", itemId);
            ReflectionTestUtils.setField(r, "visitKey", visitKey);
            ReflectionTestUtils.setField(r, "recordDate", LocalDate.parse(date));
            ReflectionTestUtils.setField(r, "mileage", mileage);
            ReflectionTestUtils.setField(r, "recordType", type);
            ReflectionTestUtils.setField(r, "source", type.isExternal() ? "CUSTOMER_STATEMENT" : "SHOP");
            ReflectionTestUtils.setField(r, "staffId", 11L);
            ReflectionTestUtils.setField(r, "createdAt", LocalDateTime.parse(savedAt));
            return r;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ItemRef item(long id, String name) {
        return new ItemRef(id, "CODE_" + id, name, 6, 10_000, true, (int) id);
    }

    private void stubItems() {
        when(items.findItems(anyLong(), any())).thenReturn(Map.of(1L, item(1, "엔진오일"), 2L, item(2, "에어클리너")));
    }

    @Test
    @DisplayName("API 11: visit_key로 묶어 visitDate 내림차순, 같은 날이면 나중에 저장한 방문이 먼저, 쪽 수는 방문 묶음 기준")
    void listVisits_groupsAndSorts() {
        when(repository.findByShopIdAndVehicleId(SHOP, VEHICLE)).thenReturn(List.of(
            record(1, "v-old", 1, "2026-05-14", 10_000, RecordType.REPLACE, "2026-05-14T10:00:00"),
            record(2, "v-same-early", 1, "2026-10-24", null, RecordType.REPLACE, "2026-10-24T09:00:00"),
            record(3, "v-same-late", 1, "2026-10-24", 30_000, RecordType.REPLACE, "2026-10-24T15:00:00"),
            record(4, "v-same-late", 2, "2026-10-24", 30_000, RecordType.INSPECT, "2026-10-24T15:00:00")));
        stubItems();
        when(staff.findNames(SHOP, Set.of(11L))).thenReturn(Map.of(11L, "김정비"));

        VisitListResponse result = service().listVisits(SHOP, VEHICLE, PageLimit.of(null, null));

        assertThat(result.items()).extracting(VisitRef::staffName).containsOnly("김정비");
        assertThat(result.items()).extracting(VisitRef::visitKey).containsExactly("v-same-late", "v-same-early", "v-old");
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(1);
        VisitRef latest = result.items().get(0);
        assertThat(latest.mileage()).isEqualTo(30_000);
        assertThat(latest.records()).extracting(VisitRef.VisitRecord::recordId).containsExactly(3L, 4L);
        assertThat(latest.records()).extracting(VisitRef.VisitRecord::itemName).containsExactly("엔진오일", "에어클리너");
        assertThat(latest.records()).extracting(VisitRef.VisitRecord::type).containsExactly("REPLACE", "INSPECT");
    }

    @Test
    @DisplayName("API 11: 쪽 나누기는 방문 묶음 수 기준이고 범위를 넘는 쪽은 빈 목록")
    void listVisits_paging() {
        when(repository.findByShopIdAndVehicleId(SHOP, VEHICLE)).thenReturn(List.of(
            record(1, "a", 1, "2026-01-01", null, RecordType.REPLACE, "2026-01-01T10:00:00"),
            record(2, "b", 1, "2026-02-01", null, RecordType.REPLACE, "2026-02-01T10:00:00"),
            record(3, "c", 1, "2026-03-01", null, RecordType.REPLACE, "2026-03-01T10:00:00")));
        stubItems();

        VisitListResponse second = service().listVisits(SHOP, VEHICLE, PageLimit.of(1, 1));
        assertThat(second.items()).extracting(VisitRef::visitKey).containsExactly("b");
        assertThat(second.totalElements()).isEqualTo(3);
        assertThat(second.totalPages()).isEqualTo(3);

        VisitListResponse beyond = service().listVisits(SHOP, VEHICLE, PageLimit.of(9, 1));
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("API 11: visitDate는 외부가 아닌 기록의 날짜, 외부만 있으면 그 날짜")
    void listVisits_visitDateIgnoresExternal() {
        when(repository.findByShopIdAndVehicleId(SHOP, VEHICLE)).thenReturn(List.of(
            record(1, "mixed", 1, "2026-03-01", 20_000, RecordType.REPLACE, "2026-10-01T10:00:00"),
            record(2, "mixed", 2, "2025-01-01", null, RecordType.EXTERNAL, "2026-10-01T10:00:00"),
            record(3, "ext-only", 2, "2026-06-01", null, RecordType.EXTERNAL, "2026-10-02T10:00:00")));
        stubItems();

        VisitListResponse result = service().listVisits(SHOP, VEHICLE, PageLimit.of(null, null));

        assertThat(result.items()).extracting(VisitRef::visitKey).containsExactly("ext-only", "mixed");
        assertThat(result.items().get(0).visitDate()).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(result.items().get(1).visitDate()).isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(result.items().get(1).mileage()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("API 11: 항목 이름은 한 번의 조회로 가져오고, 기록이 없으면 항목 조회 없이 빈 목록")
    void listVisits_oneItemQuery_andEmpty() {
        when(repository.findByShopIdAndVehicleId(SHOP, VEHICLE)).thenReturn(List.of(
            record(1, "a", 1, "2026-01-01", null, RecordType.REPLACE, "2026-01-01T10:00:00"),
            record(2, "b", 2, "2026-02-01", null, RecordType.REPLACE, "2026-02-01T10:00:00")));
        stubItems();
        service().listVisits(SHOP, VEHICLE, PageLimit.of(null, null));
        verify(items).findItems(SHOP, Set.of(1L, 2L));
    }

    @Test
    @DisplayName("API 11: 담당자 이름을 찾지 못해도 staffName은 null이 아닌 빈 문자열(08 11 필수 문자열)")
    void listVisits_missingStaffName() {
        when(repository.findByShopIdAndVehicleId(SHOP, VEHICLE)).thenReturn(List.of(
            record(1, "a", 1, "2026-01-01", null, RecordType.REPLACE, "2026-01-01T10:00:00")));
        stubItems();
        when(staff.findNames(SHOP, Set.of(11L))).thenReturn(Map.of());

        VisitListResponse result = service().listVisits(SHOP, VEHICLE, PageLimit.of(null, null));

        assertThat(result.items()).extracting(VisitRef::staffName).containsExactly("");
    }

    @Test
    @DisplayName("API 11: 기록이 없는 차량은 items [] 이고 항목을 조회하지 않는다")
    void listVisits_noRecords() {
        when(repository.findByShopIdAndVehicleId(SHOP, VEHICLE)).thenReturn(List.of());

        VisitListResponse result = service().listVisits(SHOP, VEHICLE, PageLimit.of(null, null));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        verifyNoInteractions(items);
    }

    @Test
    @DisplayName("X2: 차량 소속 확인이 404를 던지면 기록을 조회하지 않는다")
    void listVisits_notOwnedVehicle() {
        when(customers.getOwnedVehicle(SHOP, VEHICLE)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> service().listVisits(SHOP, VEHICLE, PageLimit.of(null, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("findRecordsByVehicle: 기록이 없는 차량도 키가 있고, 기록일·저장 시각·id 오름차순, resetsCycle은 RecordType 한 곳 값")
    void findRecordsByVehicle_keysSortAndResetsCycle() {
        when(repository.findByShopIdAndVehicleIdIn(SHOP, Set.of(VEHICLE, 99L))).thenReturn(List.of(
            record(2, "b", 1, "2026-02-01", null, RecordType.INSPECT, "2026-02-01T10:00:00"),
            record(1, "a", 1, "2026-01-01", 1_000, RecordType.REPLACE, "2026-01-01T10:00:00")));

        Map<Long, List<MaintenanceRecordRef>> result = service().findRecordsByVehicle(SHOP, List.of(VEHICLE, 99L));

        assertThat(result.get(99L)).isEmpty();
        assertThat(result.get(VEHICLE)).extracting(MaintenanceRecordRef::recordId).containsExactly(1L, 2L);
        assertThat(result.get(VEHICLE)).extracting(MaintenanceRecordRef::resetsCycle).containsExactly(true, false);
        assertThat(result.get(VEHICLE)).extracting(MaintenanceRecordRef::type).containsExactly("REPLACE", "INSPECT");
        assertThat(service().findRecordsByVehicle(SHOP, List.of())).isEmpty();
    }

    @Test
    @DisplayName("countVisits: 서로 다른 visit_key 수, 외부 기록만 있는 방문은 제외 (POL-45)")
    void countVisits_excludesExternalOnly() {
        when(repository.findByShopIdAndVehicleId(SHOP, VEHICLE)).thenReturn(List.of(
            record(1, "a", 1, "2026-01-01", null, RecordType.REPLACE, "2026-01-01T10:00:00"),
            record(2, "a", 2, "2026-01-01", null, RecordType.INSPECT, "2026-01-01T10:00:00"),
            record(3, "ext", 2, "2025-06-01", null, RecordType.EXTERNAL, "2026-02-01T10:00:00")));

        assertThat(service().countVisits(SHOP, VEHICLE)).isEqualTo(1);
    }

    @Test
    @DisplayName("findRecentVisits: 최신순 최대 limit회")
    void findRecentVisits_limit() {
        when(repository.findByShopIdAndVehicleId(SHOP, VEHICLE)).thenReturn(List.of(
            record(1, "a", 1, "2026-01-01", null, RecordType.REPLACE, "2026-01-01T10:00:00"),
            record(2, "b", 1, "2026-02-01", null, RecordType.REPLACE, "2026-02-01T10:00:00"),
            record(3, "c", 1, "2026-03-01", null, RecordType.REPLACE, "2026-03-01T10:00:00")));
        stubItems();

        assertThat(service().findRecentVisits(SHOP, VEHICLE, 2)).extracting(VisitRef::visitKey).containsExactly("c", "b");
        assertThat(service().findRecentVisits(SHOP, VEHICLE, 0)).isEmpty();
    }
}
