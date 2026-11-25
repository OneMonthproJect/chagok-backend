package com.wanted.chagok.domain.customer.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerRef;
import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerCreateResponse;
import com.wanted.chagok.domain.customer.dto.CustomerSearchResponse;
import com.wanted.chagok.domain.customer.dto.VehicleAddResponse;
import com.wanted.chagok.domain.customer.dto.VehicleDetailResponse;
import com.wanted.chagok.domain.customer.entity.Customer;
import com.wanted.chagok.domain.customer.entity.Vehicle;
import com.wanted.chagok.domain.customer.repository.CustomerRepository;
import com.wanted.chagok.domain.customer.repository.VehicleRepository;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.web.PageLimit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객·차량 검색(F-CUS-01)·등록(F-CUS-02)과 다른 도메인에 주는 읽기 계약 구현.
 * 차량 추가(API 08)·차량 상세(API 09)도 이 클래스가 맡는다. 고객 상세(API 07)는 screen/visit이 이 클래스의 읽기 계약({@code getOwnedCustomer})으로 읽는다.
 * 정비소 데이터는 모두 {@code shopId}(토큰)로만 조회한다.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService implements CustomerReader, CustomerWriter {

    private static final int MIN_MODEL_YEAR = 1980;
    private static final int MAX_MILEAGE = 2_000_000;

    private final CustomerRepository customers;
    private final VehicleRepository vehicles;
    private final Clock clock;
    private final EntityManager entityManager;

    public CustomerService(CustomerRepository customers, VehicleRepository vehicles, Clock clock,
                           EntityManager entityManager) {
        this.customers = customers;
        this.vehicles = vehicles;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    /** API 05. 비어 있는 {@code q}는 전체(최근 등록순). 검색어의 공백·하이픈은 무시한다. */
    public CustomerSearchResponse search(long shopId, String q, PageLimit limit) {
        PageRequest pageable = PageRequest.of(limit.page(), limit.size(), Sort.by(Sort.Direction.DESC, "id"));
        String keyword = q == null ? "" : q.replaceAll("[\\s-]+", "");
        Page<Customer> result = keyword.isEmpty()
            ? customers.findByShopId(shopId, pageable)
            : customers.search(shopId, likePattern(keyword), pageable);

        List<Long> customerIds = result.getContent().stream().map(Customer::getId).toList();
        Map<Long, List<CustomerSearchResponse.VehicleItem>> vehiclesByCustomer = customerIds.isEmpty()
            ? Map.of()
            : vehicles.findByShopIdAndCustomerIdInOrderByIdAsc(shopId, customerIds).stream()
                .collect(Collectors.groupingBy(Vehicle::getCustomerId,
                    Collectors.mapping(v -> new CustomerSearchResponse.VehicleItem(v.getId(), v.getPlateNumber()),
                        Collectors.toList())));

        List<CustomerSearchResponse.Item> items = result.getContent().stream()
            .map(c -> new CustomerSearchResponse.Item(c.getId(), c.getName(), c.getPhone(), c.isConsent(),
                vehiclesByCustomer.getOrDefault(c.getId(), List.of())))
            .toList();
        return new CustomerSearchResponse(items, limit.page(), limit.size(), result.getTotalElements(),
            result.getTotalPages());
    }

    /** API 06. 고객과 첫 차량을 한 트랜잭션으로 저장한다. 차량번호 중복은 막지 않는다 (04 F-CUS-02). */
    @Transactional
    public CustomerCreateResponse register(long shopId, CustomerCreateRequest request) {
        CustomerCreateRequest.VehicleInput input = request.vehicle();
        checkModelYearUpperBound(input.modelYear(), "vehicle.modelYear");
        Customer customer = customers.save(
            Customer.register(shopId, request.name(), request.phone(), request.consent()));
        Vehicle vehicle = vehicles.save(Vehicle.register(shopId, customer.getId(), input.plateNumber(),
            input.model(), input.modelYear(), input.mileage()));
        return new CustomerCreateResponse(customer.getId(), vehicle.getId());
    }

    /**
     * API 08. 기존 고객에 차량을 추가한다(차종 필수, 요청 모양은 06의 {@code vehicle}과 같다). 없거나 남의 정비소 고객이면
     * 404이고 차량을 저장하지 않는다(X17, D-63 B11). 차량번호 중복은 막지 않는다.
     */
    @Transactional
    public VehicleAddResponse addVehicle(long shopId, long customerId, CustomerCreateRequest.VehicleInput input) {
        Customer customer = getOwned(shopId, customerId);
        checkModelYearUpperBound(input.modelYear(), "modelYear");
        Vehicle vehicle = vehicles.save(Vehicle.register(shopId, customer.getId(), input.plateNumber(),
            input.model(), input.modelYear(), input.mileage()));
        return new VehicleAddResponse(customer.getId(), vehicle.getId());
    }

    /** API 09. 차량과 그 고객(연락처 전체, D-40). 없거나 남의 정비소 차량이면 404 (X2). */
    public VehicleDetailResponse getVehicleDetail(long shopId, long vehicleId) {
        Vehicle vehicle = ownedVehicle(shopId, vehicleId);
        Customer customer = getOwned(shopId, vehicle.getCustomerId());
        return VehicleDetailResponse.of(vehicle, customer);
    }

    /** 차량이 우리 정비소 것인지 확인한다. 없거나 남의 정비소 것이면 같은 404 (D-12). 차량 소속 확인은 여기 한 곳이다. */
    private Vehicle ownedVehicle(long shopId, long vehicleId) {
        return vehicles.findByIdAndShopId(vehicleId, shopId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 연식의 상한(현재 연도 + 1)은 시각이 필요해 Bean Validation 대신 {@code Clock}으로 확인한다 (D-63 B04). */
    private void checkModelYearUpperBound(Integer modelYear, String field) {
        int maxYear = LocalDate.now(clock).getYear() + 1;
        if (modelYear != null && modelYear > maxYear) {
            throw BusinessException.invalidField(field, MIN_MODEL_YEAR + "년부터 " + maxYear + "년까지 입력할 수 있습니다.");
        }
    }

    /** 고객이 우리 정비소 것인지 확인한다. 없거나 남의 정비소 것이면 같은 404 (D-12). 차량 추가(API 08)·차량 상세(API 09)와 읽기 계약 {@code getOwnedCustomer}가 쓴다. */
    public Customer getOwned(long shopId, long customerId) {
        return customers.findByIdAndShopId(customerId, shopId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    @Override
    public VehicleRef getOwnedVehicle(long shopId, long vehicleId) {
        Vehicle v = ownedVehicle(shopId, vehicleId);
        return new VehicleRef(v.getId(), v.getCustomerId(), v.getPlateNumber(), v.getModel(), v.getModelYear(),
            v.getLatestMileage());
    }

    @Override
    public Map<Long, VehicleSummary> findVehicleSummaries(long shopId, Collection<Long> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            return Map.of();
        }
        return new HashMap<>(vehicles.findSummaries(shopId, vehicleIds).stream()
            .collect(Collectors.toMap(VehicleSummary::vehicleId, Function.identity())));
    }

    @Override
    public CustomerRef getOwnedCustomer(long shopId, long customerId) {
        Customer customer = getOwned(shopId, customerId);
        List<VehicleRef> refs = vehicles.findByShopIdAndCustomerIdInOrderByIdAsc(shopId, List.of(customerId)).stream()
            .map(v -> new VehicleRef(v.getId(), v.getCustomerId(), v.getPlateNumber(), v.getModel(), v.getModelYear(),
                v.getLatestMileage()))
            .toList();
        return new CustomerRef(customer.getId(), customer.getName(), customer.getPhone(), customer.isConsent(), refs);
    }

    /**
     * 방문·정비 기록 저장 때 차량의 연식·최근 주행거리를 반영한다 (규칙은 {@link CustomerWriter#applyVisit}).
     * 읽기 전용 클래스 설정을 덮어쓰고, 호출한 쪽(maintenance)의 트랜잭션에 참여한다.
     */
    @Override
    @Transactional
    public void applyVisit(long shopId, long vehicleId, Integer modelYear, Integer mileage) {
        Vehicle vehicle = ownedVehicle(shopId, vehicleId);
        // 같은 차량의 방문 저장이 겹쳐도 최근 주행거리가 낮아지지 않도록 행을 잠그고 최신 값을 다시 읽는다.
        // saveVisit이 앞에서 차량을 읽어 영속성 컨텍스트에 올려 두었을 수 있으므로, 잠금 조회만으로는 낡은 값이 남아 refresh로 함께 읽는다.
        entityManager.refresh(vehicle, LockModeType.PESSIMISTIC_WRITE);
        // 하한 1980은 DTO @Min과 여기서 모두 확인, 상한은 등록·차량 추가와 같은 헬퍼
        if (modelYear != null && modelYear < MIN_MODEL_YEAR) {
            throw BusinessException.invalidField("vehicle.modelYear",
                MIN_MODEL_YEAR + "년부터 " + (LocalDate.now(clock).getYear() + 1) + "년까지 입력할 수 있습니다.");
        }
        checkModelYearUpperBound(modelYear, "vehicle.modelYear");
        if (mileage != null && (mileage < 0 || mileage > MAX_MILEAGE)) {
            throw BusinessException.invalidField("mileage", "0 이상 2,000,000 이하로 입력해 주세요.");
        }
        vehicle.applyVisit(modelYear, mileage);
    }

    @Override
    public List<VehicleSummary> findConsentedVehicles(long shopId) {
        return vehicles.findConsentedSummaries(shopId);
    }

    /** LIKE 특수문자({@code % _ !})를 {@code !}로 이스케이프해 검색어를 글자 그대로 찾는다. */
    private static String likePattern(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
