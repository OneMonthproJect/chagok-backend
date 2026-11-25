package com.wanted.chagok.domain.reservation.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.domain.reservation.api.ReservationRef;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationListResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationItem;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 유스케이스. 다른 도메인 계약({@code ReservationReader})을 구현하고, 기간 조회(API 15)·등록(API 16)·
 * 방문 완료(API 18)를 한다. 변경(API 19)은 이 클래스에 메서드를 더한다.
 * 다른 도메인은 {@code customer.api}·{@code item.api}로만 부른다 (CONVENTIONS 3장).
 */
@Service
public class ReservationService implements ReservationReader {

    /** 08 15번: 기간은 최대 42일(월 보기 6주), 시작·끝 날짜를 모두 포함해 센다. */
    static final int MAX_PERIOD_DAYS = 42;

    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository reservationItemRepository;
    private final CustomerReader customerReader;
    private final ItemReader itemReader;
    private final Clock clock;

    public ReservationService(ReservationRepository reservationRepository,
                              ReservationItemRepository reservationItemRepository,
                              CustomerReader customerReader,
                              ItemReader itemReader,
                              Clock clock) {
        this.reservationRepository = reservationRepository;
        this.reservationItemRepository = reservationItemRepository;
        this.customerReader = customerReader;
        this.itemReader = itemReader;
        this.clock = clock;
    }

    /**
     * 유효 예약 = {@code RESERVED} + 예약 날짜 ≥ 오늘 (D-22). 오늘은 {@code Clock}(Asia/Seoul)으로 구한다.
     * 한 차량에 둘 이상이 나와도 예외 없이 가장 이른 것 하나만 남긴다(등록 때 막는 규칙이고 DB 제약은 없다).
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, ReservationRef> findActiveByVehicleIds(long shopId, Collection<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        LocalDate today = LocalDate.now(clock);
        Map<Long, ReservationRef> active = new LinkedHashMap<>();
        // 이른 순서로 오므로 차량마다 처음 만난 것이 가장 이른 예약이다
        for (Reservation r : reservationRepository.findByVehiclesAndStatusFrom(
                shopId, vehicleIds, ReservationStatus.RESERVED, today.atStartOfDay())) {
            active.putIfAbsent(r.getVehicleId(), new ReservationRef(r.getId(), r.getVehicleId(), r.getReservedAt()));
        }
        return active;
    }

    /**
     * API 15 기간 조회 (04 F-RSV-02, X7). 취소·노쇼 포함, 시각 순, 쪽 나누기 없음. 차량·고객 요약과 예약 목적 항목
     * 이름은 각각 한 번에 가져온다(N+1 방지). 정비소는 토큰의 값만 쓴다.
     */
    @Transactional(readOnly = true)
    public ReservationListResponse list(long shopId, LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        List<Reservation> reservations = reservationRepository.findInPeriod(
            shopId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        if (reservations.isEmpty()) {
            return new ReservationListResponse(from, to, List.of());
        }

        List<Long> reservationIds = reservations.stream().map(Reservation::getId).toList();
        Set<Long> vehicleIds = reservations.stream().map(Reservation::getVehicleId).collect(Collectors.toSet());
        Map<Long, VehicleSummary> summaries = customerReader.findVehicleSummaries(shopId, vehicleIds);

        Map<Long, List<ReservationItem>> chosenByReservation = reservationItemRepository
            .findByShopIdAndReservationIdIn(shopId, reservationIds).stream()
            .collect(Collectors.groupingBy(ReservationItem::getReservationId));
        Set<Long> itemIds = chosenByReservation.values().stream().flatMap(List::stream)
            .map(ReservationItem::getServiceItemId).collect(Collectors.toSet());
        Map<Long, ItemRef> itemRefs = itemReader.findItems(shopId, itemIds);

        LocalDateTime now = LocalDateTime.now(clock);
        List<ReservationListResponse.Item> items = new ArrayList<>();
        for (Reservation r : reservations) {
            VehicleSummary vehicle = summaries.get(r.getVehicleId());
            if (vehicle == null) {
                // 예약의 차량은 복합 FK로 같은 정비소 차량임이 보장된다. 없으면 서버 버그이므로 500으로 드러낸다
                throw new IllegalStateException("예약 " + r.getId() + "의 차량 " + r.getVehicleId() + " 요약이 없다");
            }
            items.add(new ReservationListResponse.Item(
                r.getId(), r.getReservedAt(), r.getStatus(), r.needsAction(now), r.getVersion(),
                r.getVehicleId(), vehicle.plateNumber(), vehicle.customerId(), vehicle.customerName(), vehicle.phone(),
                purposeSummary(r, chosenByReservation.getOrDefault(r.getId(), List.of()), itemRefs)));
        }
        return new ReservationListResponse(from, to, items);
    }

    /**
     * API 16 예약 등록 (04 F-RSV-01, R1~R4·X4·X8·X11). 08 16번의 순서: 차량 소유(404) → 입력 검증(30분 단위·항목, 400) →
     * 날짜 ≥ 오늘(400) → 같은 차량 유효 예약 1건(409) → 저장. 예약 행과 예약 목적 행은 한 트랜잭션이다.
     * P0-핵심은 요청에 담당이 없고 등록한 계정이 담당이다(D-51).
     *
     * <p>LIMIT: 같은 차량 유효 예약 확인과 저장 사이에 다른 요청이 끼어들면 둘 다 저장된다(동시 요청 잠금은 P2,
     * changelog D-22). 같은 차량에 거의 동시에 등록하는 일이 실제로 문제가 될 때 차량 단위 잠금을 더한다.
     */
    @Transactional
    public ReservationCreateResponse create(long shopId, long staffId, ReservationCreateRequest request) {
        long vehicleId = request.vehicleId();
        customerReader.getOwnedVehicle(shopId, vehicleId);

        LocalDateTime reservedAt = request.reservedAt();
        if (reservedAt.getMinute() % 30 != 0 || reservedAt.getSecond() != 0 || reservedAt.getNano() != 0) {
            throw BusinessException.invalidField("reservedAt", "30분 단위로 입력해 주세요.");
        }
        List<Long> itemIds = purposeItemIds(request);
        if (new HashSet<>(itemIds).size() != itemIds.size()) {
            throw BusinessException.invalidField("purpose.itemIds", "같은 항목을 두 번 고를 수 없습니다.");
        }
        itemReader.requireActiveItems(shopId, itemIds, "purpose.itemIds");

        if (reservedAt.toLocalDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.RESERVATION_PAST_DATE);
        }
        ReservationRef active = findActiveByVehicleIds(shopId, List.of(vehicleId)).get(vehicleId);
        if (active != null) {
            throw new BusinessException(ErrorCode.RESERVATION_ACTIVE_EXISTS,
                Map.of("reservationId", active.reservationId()));
        }

        ReservationCreateRequest.Purpose purpose = request.purpose();
        boolean other = purpose != null && Boolean.TRUE.equals(purpose.other());
        String memo = purpose == null ? null : purpose.memo();
        Reservation saved = reservationRepository.save(
            Reservation.register(shopId, vehicleId, reservedAt, other, memo, staffId));
        if (!itemIds.isEmpty()) {
            reservationItemRepository.saveAll(
                itemIds.stream().map(itemId -> ReservationItem.of(shopId, saved.getId(), itemId)).toList());
        }
        return ReservationCreateResponse.of(saved);
    }

    /**
     * API 18 방문 완료 (04 F-RSV-04, R5·R10·R12·R13). 순서: 정비소 소유(404) → 상태(409 INVALID_STATUS_TRANSITION) →
     * 요청 version 비교(409 CONCURRENT_MODIFICATION) → 전이 → {@code @Version} 조건 저장. 하나라도 어긋나면
     * 예외가 나고 트랜잭션이 되돌려져 상태·{@code version}이 그대로다.
     */
    @Transactional
    public ReservationResponse complete(long shopId, long reservationId, int expectedVersion) {
        Reservation reservation = getOwned(shopId, reservationId);
        reservation.complete(expectedVersion);
        // 읽은 뒤 저장 사이에 다른 요청이 먼저 바꿨으면 여기서 충돌이 드러나 409가 된다. flush 뒤에야 version이 올라간다
        reservationRepository.saveAndFlush(reservation);
        return ReservationResponse.statusChanged(reservation);
    }

    /** 정비소 소속 확인의 한 곳. 없거나 다른 정비소 것이면 같은 404 (CONVENTIONS 4장, 08 X-규칙). */
    private Reservation getOwned(long shopId, long reservationId) {
        return reservationRepository.findByIdAndShopId(reservationId, shopId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 08 15번: {@code from}이 {@code to}보다 늦거나, 시작·끝을 포함해 42일을 넘으면 400 {@code INVALID_INPUT}. */
    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw BusinessException.invalidField("from", "시작일은 종료일보다 늦을 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw BusinessException.invalidField("to", "조회 기간은 최대 " + MAX_PERIOD_DAYS + "일입니다.");
        }
    }

    private static List<Long> purposeItemIds(ReservationCreateRequest request) {
        ReservationCreateRequest.Purpose purpose = request.purpose();
        if (purpose == null || purpose.itemIds() == null) {
            return List.of();
        }
        if (purpose.itemIds().stream().anyMatch(Objects::isNull)) {
            // 빈 칸을 "항목 없음"으로 삼켜 통과시키지 않는다 (05 POL-120~129)
            throw BusinessException.invalidField("purpose.itemIds", "항목 번호가 비어 있습니다.");
        }
        return List.copyOf(purpose.itemIds());
    }

    /**
     * 고른 항목 이름을 {@code ·}로 잇고, 기타를 골랐으면 맨 끝에 "기타"를 붙인다(예: {@code 배터리·기타}).
     * 항목은 화면 순서({@code sortOrder})를 따른다. 항목도 기타도 없으면 null (08 15번).
     */
    private static String purposeSummary(Reservation reservation, List<ReservationItem> chosen,
                                         Map<Long, ItemRef> itemRefs) {
        List<String> names = new ArrayList<>();
        chosen.stream()
            .map(c -> itemRefs.get(c.getServiceItemId()))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(ItemRef::sortOrder))
            .forEach(ref -> names.add(ref.name()));
        if (reservation.isPurposeOther()) {
            names.add("기타");
        }
        return names.isEmpty() ? null : String.join("·", names);
    }
}
