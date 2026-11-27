package com.wanted.chagok.domain.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest.RecordInput;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveRequest.VehicleInput;
import com.wanted.chagok.domain.maintenance.dto.VisitSaveResponse;
import com.wanted.chagok.domain.maintenance.entity.MaintenanceRecord;
import com.wanted.chagok.domain.maintenance.entity.RecordType;
import com.wanted.chagok.domain.maintenance.repository.MaintenanceRecordRepository;
import com.wanted.chagok.domain.maintenance.repository.MaintenanceRecordRevisionRepository;
import com.wanted.chagok.domain.maintenance.service.MaintenanceService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.response.ApiResponse.FieldErrorDetail;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * API 12 저장 단위 테스트 — Repository·다른 도메인 계약은 가짜(DB 없음). 검증 순서·P0-핵심 거절·D-58·한 방문 묶음을 본다.
 * Entity ↔ V1 {@code validate}, 정비소 격리, 트랜잭션 롤백, 저장 후 상태 변화는 DB 테스트(dbTest)에서 본다.
 * 오늘은 2026-11-25(Asia/Seoul)로 고정.
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceSaveServiceTest {

    private static final long SHOP = 7L;
    private static final long VEHICLE = 3L;
    private static final long STAFF = 11L;
    private static final LocalDate TODAY = LocalDate.parse("2026-11-25");

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

    private void ownedVehicle(Integer modelYear, Integer latestMileage) {
        when(vehicleWriter.getOwnedVehicleForVisit(SHOP, VEHICLE))
            .thenReturn(new VehicleRef(VEHICLE, 1L, "12가3456", "아반떼", modelYear, latestMileage));
    }

    private static RecordInput record(long itemId, RecordType type, String memo) {
        return new RecordInput(itemId, type, memo, null, null, null);
    }

    private static VisitSaveRequest request(LocalDate date, Integer mileage, Integer modelYear, RecordInput... rows) {
        return new VisitSaveRequest(date, mileage, modelYear == null ? null : new VehicleInput(modelYear), null,
            List.of(rows));
    }

    private static void assertInvalid(Throwable e, String... fields) {
        assertThat(e).isInstanceOfSatisfying(BusinessException.class, be -> {
            assertThat(be.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(be.fieldErrors()).extracting(FieldErrorDetail::field).containsExactly(fields);
        });
    }

    private void noWrites() {
        verify(vehicleWriter, never()).applyVisit(anyLong(), anyLong(), any(), any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("정상: 모든 행이 같은 visit_key·차량·담당·방문일·주행거리를 갖고, 차량 반영 뒤에 저장하며, 빈 메모는 null")
    void saves_oneVisitAcrossRows() {
        ownedVehicle(2019, 50_000);
        doAnswer(inv -> inv.getArgument(0)).when(repository).saveAll(any());

        VisitSaveResponse response = service().saveVisit(SHOP, STAFF, VEHICLE, request(TODAY, 55_000, null,
            record(1, RecordType.REPLACE, "  "), record(5, RecordType.INSPECT, " 앞 타이어 마모 ")));

        assertThat(response.vehicleId()).isEqualTo(VEHICLE);
        assertThat(response.savedCount()).isEqualTo(2);
        assertThat(UUID.fromString(response.visitKey())).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MaintenanceRecord>> captor = ArgumentCaptor.forClass(List.class);
        var order = inOrder(vehicleWriter, repository);
        order.verify(vehicleWriter).getOwnedVehicleForVisit(SHOP, VEHICLE);
        order.verify(vehicleWriter).applyVisit(SHOP, VEHICLE, null, 55_000);
        order.verify(repository).saveAll(captor.capture());
        List<MaintenanceRecord> rows = captor.getValue();
        assertThat(rows).extracting(MaintenanceRecord::getVisitKey).containsOnly(response.visitKey());
        assertThat(rows).extracting(MaintenanceRecord::getShopId).containsOnly(SHOP);
        assertThat(rows).extracting(MaintenanceRecord::getVehicleId).containsOnly(VEHICLE);
        assertThat(rows).extracting(MaintenanceRecord::getStaffId).containsOnly(STAFF);
        assertThat(rows).extracting(MaintenanceRecord::getRecordDate).containsOnly(TODAY);
        assertThat(rows).extracting(MaintenanceRecord::getMileage).containsOnly(55_000);
        assertThat(rows).extracting(MaintenanceRecord::getSource).containsOnly("SHOP");
        assertThat(rows).extracting(MaintenanceRecord::getServiceItemId).containsExactly(1L, 5L);
        assertThat(rows).extracting(MaintenanceRecord::getRecordType)
            .containsExactly(RecordType.REPLACE, RecordType.INSPECT);
        assertThat(rows).extracting(MaintenanceRecord::getMemo).containsExactly(null, "앞 타이어 마모");
    }

    @Test
    @DisplayName("방문마다 새 visit_key를 만든다")
    void newVisitKeyPerRequest() {
        ownedVehicle(2019, 50_000);
        doAnswer(inv -> inv.getArgument(0)).when(repository).saveAll(any());

        VisitSaveResponse first = service().saveVisit(SHOP, STAFF, VEHICLE,
            request(TODAY, null, null, record(1, RecordType.REPLACE, null)));
        VisitSaveResponse second = service().saveVisit(SHOP, STAFF, VEHICLE,
            request(TODAY, null, null, record(1, RecordType.REPLACE, null)));

        assertThat(first.visitKey()).isNotEqualTo(second.visitKey());
    }

    @Test
    @DisplayName("방문일: 오늘은 통과, 내일은 400(visitDate)")
    void visitDate_noFuture() {
        ownedVehicle(2019, 50_000);
        doAnswer(inv -> inv.getArgument(0)).when(repository).saveAll(any());
        service().saveVisit(SHOP, STAFF, VEHICLE, request(TODAY, null, null, record(1, RecordType.REPLACE, null)));

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE,
            request(TODAY.plusDays(1), null, null, record(1, RecordType.REPLACE, null))))
            .satisfies(e -> assertInvalid(e, "visitDate"));
    }

    @Test
    @DisplayName("X5: 없거나 남의 정비소 차량이면 404이고 이후 조회·저장을 하지 않는다")
    void notOwnedVehicle() {
        when(vehicleWriter.getOwnedVehicleForVisit(SHOP, VEHICLE)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE,
            request(TODAY, 1_000, 2019, record(1, RecordType.REPLACE, null))))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(items);
        noWrites();
    }

    private static VisitSaveRequest withMechanic(Long mechanicId, RecordInput... rows) {
        return new VisitSaveRequest(TODAY, 55_000, null, mechanicId, List.of(rows));
    }

    @SuppressWarnings("unchecked")
    private List<MaintenanceRecord> savedRows() {
        ArgumentCaptor<List<MaintenanceRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("외부: 정비일·주행거리는 외부 값으로 저장하고 출처는 고객 진술, 거절·점검은 방문일·방문 주행거리, 점검 결과는 점검에만")
    void savesExternalDeclineInspectResult() {
        ownedVehicle(2019, 50_000);
        doAnswer(inv -> inv.getArgument(0)).when(repository).saveAll(any());
        LocalDate externalDate = LocalDate.parse("2026-10-01");

        VisitSaveResponse response = service().saveVisit(SHOP, STAFF, VEHICLE, withMechanic(null,
            new RecordInput(3L, RecordType.EXTERNAL, "다른 곳에서 교체", externalDate, 48_000, null),
            record(4, RecordType.DECLINE, "다음에 하기로 함"),
            new RecordInput(5L, RecordType.INSPECT, null, null, null, "CAUTION")));

        assertThat(response.savedCount()).isEqualTo(3);
        List<MaintenanceRecord> rows = savedRows();
        MaintenanceRecord external = rows.get(0);
        assertThat(external.getRecordDate()).isEqualTo(externalDate);
        assertThat(external.getMileage()).isEqualTo(48_000);
        assertThat(external.getSource()).isEqualTo("CUSTOMER_STATEMENT");
        assertThat(external.getInspectResult()).isNull();
        MaintenanceRecord decline = rows.get(1);
        assertThat(decline.getRecordDate()).isEqualTo(TODAY);
        assertThat(decline.getMileage()).isEqualTo(55_000);
        assertThat(decline.getSource()).isEqualTo("SHOP");
        assertThat(rows.get(2).getInspectResult()).isEqualTo("CAUTION");
        assertThat(rows).extracting(MaintenanceRecord::getVisitKey).containsOnly(response.visitKey());
    }

    @Test
    @DisplayName("외부 주행거리는 비워도 되고(null 저장), 외부·거절만 있는 방문은 차량 연식·주행거리가 비어 있어도 저장된다")
    void externalAndDeclineOnly_noVehicleInfoRequired() {
        ownedVehicle(null, null);
        doAnswer(inv -> inv.getArgument(0)).when(repository).saveAll(any());

        service().saveVisit(SHOP, STAFF, VEHICLE, new VisitSaveRequest(TODAY, null, null, null, List.of(
            new RecordInput(3L, RecordType.EXTERNAL, null, LocalDate.parse("2026-10-01"), null, null),
            record(4, RecordType.DECLINE, null))));

        assertThat(savedRows().get(0).getMileage()).isNull();
    }

    @Test
    @DisplayName("외부에 정비일이 없으면 400(records[i].externalDate), 미래 정비일도 400, 주행거리 범위 밖도 400")
    void externalInputRules() {
        ownedVehicle(2019, 50_000);

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE,
            withMechanic(null, record(3, RecordType.EXTERNAL, null))))
            .satisfies(e -> assertInvalid(e, "records[0].externalDate"));
        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE, withMechanic(null,
            new RecordInput(3L, RecordType.EXTERNAL, null, TODAY.plusDays(1), 2_000_001, null))))
            .satisfies(e -> assertInvalid(e, "records[0].externalDate", "records[0].externalMileage"));
        noWrites();
    }

    @Test
    @DisplayName("외부가 아닌 기록에 외부 정비일·주행거리를 보내면 400")
    void externalFieldsOnlyForExternal() {
        ownedVehicle(2019, 50_000);

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE, withMechanic(null,
            new RecordInput(1L, RecordType.REPLACE, null, LocalDate.parse("2026-10-01"), 500, null))))
            .satisfies(e -> assertInvalid(e, "records[0].externalDate", "records[0].externalMileage"));
        noWrites();
    }

    @Test
    @DisplayName("점검 결과는 점검일 때만, GOOD·CAUTION만 받는다")
    void inspectResultRules() {
        ownedVehicle(2019, 50_000);

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE, withMechanic(null,
            new RecordInput(1L, RecordType.REPLACE, null, null, null, "GOOD"),
            new RecordInput(2L, RecordType.DECLINE, null, null, null, "GOOD"),
            new RecordInput(3L, RecordType.INSPECT, null, null, null, "BAD"))))
            .satisfies(e -> assertInvalid(e, "records[0].inspectResult", "records[1].inspectResult",
                "records[2].inspectResult"));
        noWrites();
    }

    @Test
    @DisplayName("담당: mechanicId가 있으면 모든 행의 담당이 되고, 없으면 저장한 계정")
    void mechanicAssigned() {
        ownedVehicle(2019, 50_000);
        doAnswer(inv -> inv.getArgument(0)).when(repository).saveAll(any());
        when(staff.findNames(SHOP, List.of(2L))).thenReturn(Map.of(2L, "정비사"));

        service().saveVisit(SHOP, STAFF, VEHICLE,
            withMechanic(2L, record(1, RecordType.REPLACE, null), record(4, RecordType.DECLINE, null)));

        assertThat(savedRows()).extracting(MaintenanceRecord::getStaffId).containsOnly(2L);
    }

    @Test
    @DisplayName("X13: mechanicId가 없는 직원·남의 정비소 직원이면 400(mechanicId)이고 저장하지 않는다")
    void mechanicNotInShop() {
        ownedVehicle(2019, 50_000);
        when(staff.findNames(SHOP, List.of(99L))).thenReturn(Map.of());

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE,
            withMechanic(99L, record(1, RecordType.REPLACE, null))))
            .satisfies(e -> assertInvalid(e, "mechanicId"));
        noWrites();
    }

    @Test
    @DisplayName("같은 항목을 두 번 넣으면 400이고 두 번째 위치(records[i].itemId)를 알린다")
    void duplicateItem() {
        ownedVehicle(2019, 50_000);

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE, request(TODAY, 1_000, null,
            record(1, RecordType.REPLACE, null), record(5, RecordType.INSPECT, null),
            record(1, RecordType.INSPECT, null))))
            .satisfies(e -> assertInvalid(e, "records[2].itemId"));
        verifyNoInteractions(items);
        noWrites();
    }

    @Test
    @DisplayName("X11: 쓰는 항목이 아니면(남의 정비소·없음·비활성) item 계약이 400을 던지고 저장하지 않는다")
    void itemNotUsable() {
        ownedVehicle(2019, 50_000);
        doThrow(BusinessException.invalidField("records[].itemId", "사용할 수 없는 항목입니다."))
            .when(items).requireActiveItems(SHOP, List.of(1L, 99L), "records[].itemId");

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE, request(TODAY, 1_000, null,
            record(1, RecordType.REPLACE, null), record(99, RecordType.INSPECT, null))))
            .satisfies(e -> assertInvalid(e, "records[].itemId"));
        noWrites();
    }

    @Test
    @DisplayName("D-58: 차량에 연식·주행거리가 비어 있는데 요청에 없으면 400이고, 둘 다 비었으면 둘 다 알린다")
    void vehicleInfoRequiredWhenEmpty() {
        ownedVehicle(null, null);

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE,
            request(TODAY, null, null, record(1, RecordType.REPLACE, null))))
            .satisfies(e -> assertInvalid(e, "vehicle.modelYear", "mileage"));
        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE,
            request(TODAY, 1_000, null, record(1, RecordType.REPLACE, null))))
            .satisfies(e -> assertInvalid(e, "vehicle.modelYear"));
        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE,
            request(TODAY, null, 2019, record(1, RecordType.REPLACE, null))))
            .satisfies(e -> assertInvalid(e, "mileage"));
        noWrites();
    }

    @Test
    @DisplayName("D-58: 비어 있는 값만 요청에 있으면 저장하고, 차량에 이미 값이 있으면 요청에 없어도 된다")
    void vehicleInfoNotRequiredWhenPresent() {
        doAnswer(inv -> inv.getArgument(0)).when(repository).saveAll(any());

        ownedVehicle(null, 40_000);
        service().saveVisit(SHOP, STAFF, VEHICLE, request(TODAY, null, 2019, record(1, RecordType.REPLACE, null)));
        verify(vehicleWriter).applyVisit(SHOP, VEHICLE, 2019, null);

        ownedVehicle(2019, 40_000);
        VisitSaveResponse response = service().saveVisit(SHOP, STAFF, VEHICLE,
            request(TODAY, null, null, record(1, RecordType.INSPECT, null)));
        assertThat(response.savedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("차량 반영이 실패하면(범위 밖 400 등) 기록을 저장하지 않는다")
    void vehicleWriterFails_nothingSaved() {
        ownedVehicle(null, null);
        doThrow(BusinessException.invalidField("mileage", "0 이상 2,000,000 이하로 입력해 주세요."))
            .when(vehicleWriter).applyVisit(anyLong(), anyLong(), any(), any());

        assertThatThrownBy(() -> service().saveVisit(SHOP, STAFF, VEHICLE,
            request(TODAY, 9_999_999, 2019, record(1, RecordType.REPLACE, null))))
            .satisfies(e -> assertInvalid(e, "mileage"));
        verify(repository, never()).saveAll(any());
    }
}
