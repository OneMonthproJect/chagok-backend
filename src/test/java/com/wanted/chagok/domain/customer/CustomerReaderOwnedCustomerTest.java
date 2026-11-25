package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.customer.api.CustomerRef;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.customer.entity.Customer;
import com.wanted.chagok.domain.customer.entity.Vehicle;
import com.wanted.chagok.domain.customer.repository.CustomerRepository;
import com.wanted.chagok.domain.customer.repository.VehicleRepository;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code CustomerReader.getOwnedCustomer} 단위 테스트 — Repository는 가짜(DB 없음). 값 옮김·차량 순서 전달·404 순서를 본다.
 * 실제 쿼리와 정비소 격리는 {@code CustomerReaderDbTest}(dbTest)에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class CustomerReaderOwnedCustomerTest {

    private static final long SHOP = 7L;
    private static final long CUSTOMER = 10L;

    @Mock
    private CustomerRepository customers;

    @Mock
    private VehicleRepository vehicles;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private CustomerService service() {
        return new CustomerService(customers, vehicles, clock, org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class));
    }

    private static Customer customer() {
        Customer c = Customer.register(SHOP, "오세훈", "010-0000-0010", true);
        ReflectionTestUtils.setField(c, "id", CUSTOMER);
        return c;
    }

    private static Vehicle vehicle(long id, String plate, String model, Integer year, Integer mileage) {
        Vehicle v = Vehicle.register(SHOP, CUSTOMER, plate, model, year, mileage);
        ReflectionTestUtils.setField(v, "id", id);
        return v;
    }

    @Test
    @DisplayName("고객 정보(전체 연락처·동의)와 차량 목록을 값으로 옮기고, 비어 있는 연식·주행거리는 null 그대로")
    void mapsCustomerAndVehicles() {
        when(customers.findByIdAndShopId(CUSTOMER, SHOP)).thenReturn(Optional.of(customer()));
        when(vehicles.findByShopIdAndCustomerIdInOrderByIdAsc(SHOP, List.of(CUSTOMER))).thenReturn(List.of(
            vehicle(10, "55차0010", "쏘나타", 2018, 97_000), vehicle(11, "66카0011", "K3", null, null)));

        CustomerRef ref = service().getOwnedCustomer(SHOP, CUSTOMER);

        assertThat(ref.customerId()).isEqualTo(CUSTOMER);
        assertThat(ref.name()).isEqualTo("오세훈");
        assertThat(ref.phone()).isEqualTo("010-0000-0010");
        assertThat(ref.consent()).isTrue();
        assertThat(ref.vehicles()).containsExactly(
            new VehicleRef(10, CUSTOMER, "55차0010", "쏘나타", 2018, 97_000),
            new VehicleRef(11, CUSTOMER, "66카0011", "K3", null, null));
    }

    @Test
    @DisplayName("차량이 없는 고객은 빈 목록")
    void noVehicles() {
        when(customers.findByIdAndShopId(CUSTOMER, SHOP)).thenReturn(Optional.of(customer()));
        when(vehicles.findByShopIdAndCustomerIdInOrderByIdAsc(SHOP, List.of(CUSTOMER))).thenReturn(List.of());

        assertThat(service().getOwnedCustomer(SHOP, CUSTOMER).vehicles()).isEmpty();
    }

    @Test
    @DisplayName("X1: 없거나 남의 정비소 고객이면 404 NOT_FOUND이고 차량은 조회하지 않는다")
    void notOwnedCustomer() {
        when(customers.findByIdAndShopId(CUSTOMER, SHOP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getOwnedCustomer(SHOP, CUSTOMER))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(vehicles, never()).findByShopIdAndCustomerIdInOrderByIdAsc(SHOP, List.of(CUSTOMER));
    }
}
