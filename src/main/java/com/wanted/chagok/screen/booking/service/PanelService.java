package com.wanted.chagok.screen.booking.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.cycle.api.CycleRecord;
import com.wanted.chagok.domain.cycle.api.CycleRule;
import com.wanted.chagok.domain.cycle.api.ItemCycleCalculator;
import com.wanted.chagok.domain.cycle.api.ItemCycleResult;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.maintenance.api.MaintenanceReader;
import com.wanted.chagok.domain.maintenance.api.MaintenanceRecordRef;
import com.wanted.chagok.domain.reservation.api.ReservationDetail;
import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.screen.booking.dto.PanelResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 17 예약 상세 패널 조립 (08 17번, F-RSV-03). 저장·상태 변경 없이 다른 도메인의 {@code api}를 읽어 한 응답으로 만든다
 * (CONVENTIONS 5장). 상태 판정은 {@code cycle}만 부르고 여기서 날짜·km를 다시 비교하지 않는다.
 *
 * <p>항목 상태는 같은 차량의 API 10·14와 같은 값이어야 한다(08 17번). 그래서 10을 주행거리 없이 부른 것과 같은 계산
 * ({@code cycle})을 쓴다. {@code screen}끼리는 서로 부르지 않으므로(CONVENTIONS 3장 의존 방향) {@code screen/visit}의
 * 서비스를 쓰지 않고 같은 {@code api}를 직접 읽는다.
 */
@Service
@Transactional(readOnly = true)
public class PanelService {

    /** 08 17번: 최근 방문은 최대 2회 (D-54). */
    static final int RECENT_VISIT_LIMIT = 2;

    private final ReservationReader reservationReader;
    private final CustomerReader customerReader;
    private final MaintenanceReader maintenanceReader;
    private final ItemReader itemReader;
    private final Clock clock;
    private final ItemCycleCalculator calculator = ItemCycleCalculator.standard();

    public PanelService(ReservationReader reservationReader, CustomerReader customerReader,
                        MaintenanceReader maintenanceReader, ItemReader itemReader, Clock clock) {
        this.reservationReader = reservationReader;
        this.customerReader = customerReader;
        this.maintenanceReader = maintenanceReader;
        this.itemReader = itemReader;
        this.clock = clock;
    }

    public PanelResponse getPanel(long shopId, long reservationId) {
        // 없거나 다른 정비소 예약이면 여기서 404 (X3)
        ReservationDetail reservation = reservationReader.getOwnedDetail(shopId, reservationId);
        long vehicleId = reservation.vehicleId();
        VehicleRef vehicle = customerReader.getOwnedVehicle(shopId, vehicleId);
        VehicleSummary customer = customerReader.findVehicleSummaries(shopId, List.of(vehicleId)).get(vehicleId);
        if (customer == null) {
            // 근거: 차량 소속 확인을 통과했는데 고객 요약이 없으면 데이터 불일치라 없는 것과 같게 404로 본다. 08 17번의 "P1 익명화 고객은 빈 값"은 P1에서 정한다
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        return new PanelResponse(
            reservationBlock(shopId, reservation),
            new PanelResponse.CustomerBlock(customer.customerId(), customer.customerName(), customer.phone(),
                customer.consent()),
            new PanelResponse.VehicleBlock(vehicle.vehicleId(), vehicle.plateNumber(), vehicle.model(),
                vehicle.modelYear(), vehicle.latestMileage()),
            maintenanceReader.findRecentVisits(shopId, vehicleId, RECENT_VISIT_LIMIT),
            maintenanceReader.countVisits(shopId, vehicleId),
            itemStatuses(shopId, vehicleId, new HashSet<>(reservation.purposeItemIds())));
    }

    /** 예약 목적 항목 이름은 한 번에 가져오고, 줄 순서는 항목 {@code sortOrder} 순이다(08 15번 purposeSummary와 같다). */
    private PanelResponse.ReservationBlock reservationBlock(long shopId, ReservationDetail reservation) {
        Map<Long, ItemRef> byId = itemReader.findItems(shopId, reservation.purposeItemIds());
        List<PanelResponse.PurposeItem> items = reservation.purposeItemIds().stream()
            .map(byId::get)
            // 근거: 예약 목적 항목은 복합 FK(정비소·항목)로 같은 정비소의 항목임이 보장된다(07 6.2). 없는 id는 정상 데이터에는 없으므로
            // 패널 전체를 깨뜨리지 않으려고 건너뛴다(비활성 항목은 findItems가 이름까지 돌려주므로 여기서 빠지지 않는다)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(ItemRef::sortOrder))
            .map(item -> new PanelResponse.PurposeItem(item.itemId(), item.name()))
            .toList();
        return new PanelResponse.ReservationBlock(reservation.reservationId(), reservation.reservedAt(),
            reservation.status(), reservation.needsAction(), reservation.version(),
            new PanelResponse.Purpose(items, reservation.purposeOther(), reservation.purposeMemo()));
    }

    /** 쓰는 항목 전부를 {@code sortOrder} 순으로. 주행거리 없이 날짜 기준만 판정한다. 목적 항목({@code highlighted})도 자리를 옮기지 않는다(D-63 B02). */
    private List<PanelResponse.ItemStatusBlock> itemStatuses(long shopId, long vehicleId, Set<Long> purposeItemIds) {
        LocalDate today = LocalDate.now(clock);
        List<ItemRef> items = itemReader.findActiveItems(shopId);
        Map<Long, List<CycleRecord>> recordsByItem = maintenanceReader
            .findRecordsByVehicle(shopId, List.of(vehicleId))
            .getOrDefault(vehicleId, List.<MaintenanceRecordRef>of()).stream()
            .collect(Collectors.groupingBy(MaintenanceRecordRef::itemId,
                Collectors.mapping(PanelService::toCycleRecord, Collectors.toList())));
        return items.stream()
            .map(item -> {
                ItemCycleResult r = calculator.calculate(new CycleRule(item.cycleMonths(), item.cycleKm()),
                    recordsByItem.getOrDefault(item.itemId(), List.of()), today, null);
                return new PanelResponse.ItemStatusBlock(item.itemId(), item.code(), item.name(), r.status(),
                    r.dueReason(), r.baseRecord(), r.nextDueDate(), r.nextDueMileage(),
                    purposeItemIds.contains(item.itemId()));
            })
            .toList();
    }

    private static CycleRecord toCycleRecord(MaintenanceRecordRef r) {
        return new CycleRecord(r.date(), r.mileage(), r.resetsCycle(), r.createdAt(), r.recordId(), r.type());
    }
}
