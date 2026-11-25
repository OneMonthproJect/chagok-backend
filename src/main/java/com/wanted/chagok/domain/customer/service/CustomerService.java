package com.wanted.chagok.domain.customer.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerCreateResponse;
import com.wanted.chagok.domain.customer.dto.CustomerSearchResponse;
import com.wanted.chagok.domain.customer.entity.Customer;
import com.wanted.chagok.domain.customer.entity.Vehicle;
import com.wanted.chagok.domain.customer.repository.CustomerRepository;
import com.wanted.chagok.domain.customer.repository.VehicleRepository;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.web.PageLimit;
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
 * 이후 이슈(#24 차량 추가, #43 고객 상세)가 메서드를 더한다. 정비소 데이터는 모두 {@code shopId}(토큰)로만 조회한다.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService implements CustomerReader {

    private static final int MIN_MODEL_YEAR = 1980;

    private final CustomerRepository customers;
    private final VehicleRepository vehicles;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, VehicleRepository vehicles, Clock clock) {
        this.customers = customers;
        this.vehicles = vehicles;
        this.clock = clock;
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
        int maxYear = LocalDate.now(clock).getYear() + 1;
        if (input.modelYear() != null && input.modelYear() > maxYear) {
            throw BusinessException.invalidField("vehicle.modelYear",
                MIN_MODEL_YEAR + "년부터 " + maxYear + "년까지 입력할 수 있습니다.");
        }
        Customer customer = customers.save(
            Customer.register(shopId, request.name(), request.phone(), request.consent()));
        Vehicle vehicle = vehicles.save(Vehicle.register(shopId, customer.getId(), input.plateNumber(),
            input.model(), input.modelYear(), input.mileage()));
        return new CustomerCreateResponse(customer.getId(), vehicle.getId());
    }

    /** 고객이 우리 정비소 것인지 확인한다. 없거나 남의 정비소 것이면 같은 404 (D-12). 같은 도메인의 다음 이슈(#24·#43)가 쓴다. */
    public Customer getOwned(long shopId, long customerId) {
        return customers.findByIdAndShopId(customerId, shopId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    @Override
    public VehicleRef getOwnedVehicle(long shopId, long vehicleId) {
        Vehicle v = vehicles.findByIdAndShopId(vehicleId, shopId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
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

    /** LIKE 특수문자({@code % _ !})를 {@code !}로 이스케이프해 검색어를 글자 그대로 찾는다. */
    private static String likePattern(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
