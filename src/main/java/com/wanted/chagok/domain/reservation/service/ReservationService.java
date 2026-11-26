package com.wanted.chagok.domain.reservation.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.reservation.api.ReservationDay;
import com.wanted.chagok.domain.reservation.api.ReservationDetail;
import com.wanted.chagok.domain.reservation.api.ReservationReader;
import com.wanted.chagok.domain.reservation.api.ReservationRef;
import com.wanted.chagok.domain.reservation.api.ReservationSummary;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationCreateResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationListResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationResponse;
import com.wanted.chagok.domain.reservation.dto.ReservationStartRequest;
import com.wanted.chagok.domain.reservation.dto.ReservationUpdateRequest;
import com.wanted.chagok.domain.reservation.entity.Reservation;
import com.wanted.chagok.domain.reservation.entity.ReservationItem;
import com.wanted.chagok.domain.reservation.entity.ReservationStatus;
import com.wanted.chagok.domain.reservation.repository.ReservationItemRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.response.ApiResponse.FieldErrorDetail;
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
 * 방문 완료(API 18)·변경(API 19)·방문 접수(API 39)를 한다.
 * 다른 도메인은 {@code customer.api}·{@code item.api}로만 부른다 (CONVENTIONS 3장).
 */
@Service
public class ReservationService implements ReservationReader {

    /** 08 15번: 기간은 최대 42일(월 보기 6주), 시작·끝 날짜를 모두 포함해 센다. */
    static final int MAX_PERIOD_DAYS = 42;

    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository reservationItemRepository;
    private final CustomerReader customerReader;
    private final CustomerWriter customerWriter;
    private final ItemReader itemReader;
    private final Clock clock;

    public ReservationService(ReservationRepository reservationRepository,
                              ReservationItemRepository reservationItemRepository,
                              CustomerReader customerReader,
                              CustomerWriter customerWriter,
                              ItemReader itemReader,
                              Clock clock) {
        this.reservationRepository = reservationRepository;
        this.reservationItemRepository = reservationItemRepository;
        this.customerReader = customerReader;
        this.customerWriter = customerWriter;
        this.itemReader = itemReader;
        this.clock = clock;
    }

    /**
     * 유효 예약 = {@code RESERVED} + 예약 날짜 ≥ 오늘 (D-22), 또는 {@code IN_PROGRESS}(날짜 무관, D-52). 오늘은 {@code Clock}(Asia/Seoul)으로 구한다.
     * 한 차량에 둘 이상이 나와도 예외 없이 가장 이른 것 하나만 남긴다(등록 때 막는 규칙이고 DB 제약은 없다).
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, ReservationRef> findActiveByVehicleIds(long shopId, Collection<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ReservationRef> active = new LinkedHashMap<>();
        // 이른 순서로 오므로 차량마다 처음 만난 것이 가장 이른 예약이다
        for (Reservation r : activeCandidates(shopId, vehicleIds)) {
            active.putIfAbsent(r.getVehicleId(), new ReservationRef(r.getId(), r.getVehicleId(), r.getReservedAt()));
        }
        return active;
    }

    /**
     * 홈의 오늘 예약(API 04)용 하루치 조회. 목록은 15번(기간 조회)의 조립을 그대로 쓴다(같은 모양, 같은 N+1 방지).
     * 목록은 취소를 포함하고, 건수만 취소를 뺀다(D-63 B05). 방문 완료는 건수에 포함한다.
     */
    // LIMIT: 정비소·하루 예약 수십 건 이하 가정(08 15번과 같다, 쪽 나누기 없음) / 넘으면 쪽 나누기를 다시 정한다
    @Override
    @Transactional(readOnly = true)
    public ReservationDay findByDate(long shopId, LocalDate date) {
        var items = list(shopId, date, date).items();
        List<ReservationSummary> reservations = items.stream()
            .map(i -> new ReservationSummary(i.reservationId(), i.reservedAt(), i.status().name(), i.needsAction(),
                i.version(), i.vehicleId(), i.plateNumber(), i.customerId(), i.customerName(), i.phone(),
                i.purposeSummary()))
            .toList();
        int active = (int) reservations.stream().filter(r -> !ReservationStatus.CANCELED.name().equals(r.status())).count();
        return new ReservationDay(active, reservations);
    }

    /** 예약 상세 패널(API 17)용 한 건 조회. 소속 확인은 {@link #getOwned} 한 곳, "처리 필요"는 Entity가 판정한다(D-23). */
    @Override
    @Transactional(readOnly = true)
    public ReservationDetail getOwnedDetail(long shopId, long reservationId) {
        Reservation r = getOwned(shopId, reservationId);
        List<Long> itemIds = reservationItemRepository.findByShopIdAndReservationIdIn(shopId, List.of(r.getId())).stream()
            .map(ReservationItem::getServiceItemId)
            .toList();
        return new ReservationDetail(r.getId(), r.getVehicleId(), r.getReservedAt(), r.getStatus().name(),
            r.needsAction(LocalDateTime.now(clock)), r.getVersion(), itemIds, r.isPurposeOther(), r.getPurposeMemo());
    }

    /**
     * 유효 예약 판정의 한 곳 (D-22·D-52): {@code RESERVED} + 예약 날짜 ≥ 오늘, 또는 {@code IN_PROGRESS}(날짜 무관). 이른 순.
     * 조회(14)·등록(16)·변경(19)이 같이 쓴다. 어제 날짜의 진행중 예약도 유효 예약이다(R23).
     */
    private List<Reservation> activeCandidates(long shopId, Collection<Long> vehicleIds) {
        List<Reservation> reserved = reservationRepository.findByVehiclesAndStatusFrom(
            shopId, vehicleIds, ReservationStatus.RESERVED, LocalDate.now(clock).atStartOfDay());
        List<Reservation> inProgress = reservationRepository.findByVehiclesAndStatus(
            shopId, vehicleIds, ReservationStatus.IN_PROGRESS);
        if (inProgress.isEmpty()) {
            return reserved;
        }
        List<Reservation> merged = new ArrayList<>(reserved);
        merged.addAll(inProgress);
        merged.sort(Comparator.comparing(Reservation::getReservedAt).thenComparing(Reservation::getId));
        return merged;
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
        requireHalfHour(reservedAt);
        List<Long> itemIds = purposeItemIds(request.purpose());
        requireValidItems(shopId, itemIds);
        requireNotPastDate(reservedAt);
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

    /**
     * API 39 방문 접수 (04 F-RSV-09, R21·X14). 순서(08 18·20과 같은 공통 순서): 정비소 소유(404) → 상태 {@code RESERVED}(409
     * INVALID_STATUS_TRANSITION) → 요청 version 비교(409 CONCURRENT_MODIFICATION) → 차량 연식·주행거리(비어 있으면 필수, 400) →
     * 전이 → 저장. 차량 행은 접수 때 연식·주행거리를 반영하므로 잠가서 읽는다(12번과 같은 방식). 하나라도 어긋나면 예외가 나고
     * 트랜잭션이 되돌려져 예약 상태·{@code version}·차량 값이 그대로다. 접수 뒤 정비 기록은 별도 저장이다(05 POL-95).
     */
    @Transactional
    public ReservationResponse start(long shopId, long reservationId, ReservationStartRequest request) {
        Reservation reservation = getOwned(shopId, reservationId);
        reservation.assertChangeable(request.version());

        VehicleRef vehicle = customerWriter.getOwnedVehicleForVisit(shopId, reservation.getVehicleId());
        requireVehicleInfo(vehicle, request.modelYear(), request.mileage());
        // 범위 검증과 "비어 있을 때만 채움·더 클 때만 갱신" 규칙은 customer 한 곳에 있다 (D-58)
        customerWriter.applyVisit(shopId, reservation.getVehicleId(), request.modelYear(), request.mileage());

        reservation.start(request.version());
        reservationRepository.saveAndFlush(reservation);
        return ReservationResponse.changed(reservation);
    }

    /** 08 39번 D-58: 차량의 연식·최근 주행거리가 비어 있는데 요청에도 없으면 400 (필드별로 모아서 알린다). */
    private static void requireVehicleInfo(VehicleRef vehicle, Integer modelYear, Integer mileage) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (vehicle.modelYear() == null && modelYear == null) {
            errors.add(new FieldErrorDetail("modelYear", "차량의 연식이 비어 있어 입력해 주세요."));
        }
        if (vehicle.latestMileage() == null && mileage == null) {
            errors.add(new FieldErrorDetail("mileage", "차량의 주행거리가 비어 있어 입력해 주세요."));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, errors, null, null);
        }
    }

    /**
     * API 19 예약 변경 (04 F-RSV-06, R7·R8·R9·R11·R14). 보낸 필드만 바꾼다. 순서(08 19번): 바꿀 필드 없음(400, 소유 확인보다 먼저) →
     * 정비소 소유(404) → 상태 {@code RESERVED}(409) → {@code version} 일치(409) → 30분 단위(400) → 항목(400) → 날짜(400) →
     * 같은 차량 유효 예약 1건(자기 자신 제외, 409) → 변경. 하나라도 어긋나면 아무것도 바꾸지 않는다.
     * 날짜 규칙과 같은 차량 유효 예약 확인은 <b>일시({@code reservedAt})를 보낼 때만</b> 한다(08 19번). 예약 목적·메모만 바꿀 때는
     * 유효 예약이 늘지 않으므로 하지 않아, 지난 날짜 예약의 메모도 다른 예약 때문에 막히지 않는다.
     * 예약 목적 항목은 지운 뒤 다시 넣고, 그 항목만 바뀌어도 {@code version}이 오르도록 {@code Reservation.change}가 수정 시각을
     * 갱신한다(R11). 일시·메모와 함께 바뀌어도 {@code version}은 한 번만 오른다({@code @Version}, flush의 UPDATE).
     */
    @Transactional
    public ReservationResponse update(long shopId, long reservationId, ReservationUpdateRequest request) {
        if (!request.hasChange()) {
            throw BusinessException.invalidField("reservedAt", "바꿀 항목을 하나 이상 보내 주세요.");
        }
        Reservation reservation = getOwned(shopId, reservationId);
        reservation.assertChangeable(request.version());

        // 검증 순서는 등록(16)과 같다: 30분 단위 → 항목 → 날짜
        LocalDateTime reservedAt = request.reservedAt();
        if (reservedAt != null) {
            requireHalfHour(reservedAt);
        }
        ReservationCreateRequest.Purpose purpose = request.purpose();
        List<Long> itemIds = purpose == null || purpose.itemIds() == null ? null : purposeItemIds(purpose);
        if (itemIds != null) {
            requireValidItems(shopId, itemIds);
        }
        if (reservedAt != null) {
            requireNotPastDate(reservedAt);
            // 근거: 08 19번 — 유효 예약이 둘이 되는 길은 일시를 바꿔 지난 예약을 오늘 이후로 옮기는 것뿐이다.
            // 메모·항목만 바꾸면 유효 여부가 그대로라 검사하지 않는다(지난 예약의 메모가 다른 예약 때문에 막히지 않게).
            activeCandidates(shopId, List.of(reservation.getVehicleId())).stream()
                .filter(r -> !r.getId().equals(reservation.getId()))
                .findFirst()
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.RESERVATION_ACTIVE_EXISTS,
                        Map.of("reservationId", existing.getId()));
                });
        }

        reservation.change(LocalDateTime.now(clock), reservedAt, purpose == null ? null : purpose.other(), purpose == null ? null : purpose.memo());
        if (itemIds != null) {
            reservationItemRepository.deleteAllOf(shopId, reservation.getId());
            if (!itemIds.isEmpty()) {
                reservationItemRepository.saveAll(
                    itemIds.stream().map(itemId -> ReservationItem.of(shopId, reservation.getId(), itemId)).toList());
            }
        }
        reservationRepository.saveAndFlush(reservation);
        return ReservationResponse.changed(reservation);
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

    /** 08 16·19번 R1: 분이 00·30이고 초가 00. */
    private static void requireHalfHour(LocalDateTime reservedAt) {
        if (reservedAt.getMinute() % 30 != 0 || reservedAt.getSecond() != 0 || reservedAt.getNano() != 0) {
            throw BusinessException.invalidField("reservedAt", "30분 단위로 입력해 주세요.");
        }
    }

    /** 08 16·19번 R2·R7: 날짜가 어제 이전이면 400. 오늘의 지난 시각은 허용한다 (D-23). */
    private void requireNotPastDate(LocalDateTime reservedAt) {
        if (reservedAt.toLocalDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.RESERVATION_PAST_DATE);
        }
    }

    /** 08 16·19번 X11: 항목 중복 없음, 그 정비소의 쓰는 항목만. */
    private void requireValidItems(long shopId, List<Long> itemIds) {
        if (new HashSet<>(itemIds).size() != itemIds.size()) {
            throw BusinessException.invalidField("purpose.itemIds", "같은 항목을 두 번 고를 수 없습니다.");
        }
        itemReader.requireActiveItems(shopId, itemIds, "purpose.itemIds");
    }

    private static List<Long> purposeItemIds(ReservationCreateRequest.Purpose purpose) {
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
