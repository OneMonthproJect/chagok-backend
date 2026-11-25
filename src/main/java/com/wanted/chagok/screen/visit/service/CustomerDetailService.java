package com.wanted.chagok.screen.visit.service;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.CustomerRef;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.screen.visit.dto.CustomerDetailResponse;
import com.wanted.chagok.screen.visit.service.ItemStatusService.StatusCount;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 07 고객 상세 (04 F-CUS-04, 08 07, Q14 결정 (a)). 고객·차량은 {@code customer.api}가, 차량별 도래·임박 수는 API 10과 같은
 * 판정({@link ItemStatusService#countStatuses})이 준다. 여기서는 응답만 조립하고 상태를 직접 판정하지 않는다 (CONVENTIONS 4·5장).
 */
@Service
@Transactional(readOnly = true)
public class CustomerDetailService {

    private final CustomerReader customerReader;
    private final ItemStatusService itemStatusService;

    public CustomerDetailService(CustomerReader customerReader, ItemStatusService itemStatusService) {
        this.customerReader = customerReader;
        this.itemStatusService = itemStatusService;
    }

    /** 없거나 다른 정비소 고객이면 404 (X1). 차량이 없으면 상태 조회 없이 {@code vehicles: []}. */
    public CustomerDetailResponse getCustomerDetail(long shopId, long customerId) {
        CustomerRef customer = customerReader.getOwnedCustomer(shopId, customerId);

        Map<Long, StatusCount> counts = itemStatusService.countStatuses(shopId,
            customer.vehicles().stream().map(VehicleRef::vehicleId).toList());
        StatusCount none = new StatusCount(0, 0);

        List<CustomerDetailResponse.Vehicle> vehicles = customer.vehicles().stream()
            .map(v -> {
                StatusCount c = counts.getOrDefault(v.vehicleId(), none);
                return new CustomerDetailResponse.Vehicle(v.vehicleId(), v.plateNumber(), v.model(), v.modelYear(),
                    v.latestMileage(), c.dueCount(), c.soonCount());
            })
            .toList();
        return new CustomerDetailResponse(customer.customerId(), customer.name(), customer.phone(), customer.consent(),
            vehicles);
    }
}
