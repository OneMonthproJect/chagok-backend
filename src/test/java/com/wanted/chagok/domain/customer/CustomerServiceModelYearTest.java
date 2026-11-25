package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.entity.Customer;
import com.wanted.chagok.domain.customer.entity.Vehicle;
import com.wanted.chagok.domain.customer.repository.CustomerRepository;
import com.wanted.chagok.domain.customer.repository.VehicleRepository;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 연식 상한(현재 연도 + 1, D-63 B04) 검사를 06(고객 등록)과 08(차량 추가)이 한 메서드로 함께 쓴다.
 * 시각을 고정해 DB 없이 두 경로의 필드 이름·경계·"저장하지 않음"을 확인한다. 실제 저장·격리는 DB 테스트
 * ({@code CustomerServiceDbTest}·{@code VehicleServiceDbTest})가 본다.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceModelYearTest {

    private static final long SHOP = 3L;
    private static final long CUSTOMER = 11L;

    // 2026-11-24 15:00 (Asia/Seoul) → 연식 상한 2027
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ClockConfig.SEOUL);

    @Mock
    private CustomerRepository customers;

    @Mock
    private VehicleRepository vehicles;

    private CustomerService service() {
        return new CustomerService(customers, vehicles, clock);
    }

    private static CustomerCreateRequest.VehicleInput vehicle(Integer modelYear) {
        return new CustomerCreateRequest.VehicleInput("12가3456", "쏘나타", modelYear, 1000);
    }

    private static CustomerCreateRequest registerRequest(Integer modelYear) {
        return new CustomerCreateRequest("홍길동", "010-1234-5678", true, vehicle(modelYear));
    }

    private void stubSaves() {
        when(customers.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", CUSTOMER);
            return c;
        });
        when(vehicles.save(any(Vehicle.class))).thenAnswer(inv -> {
            Vehicle v = inv.getArgument(0);
            ReflectionTestUtils.setField(v, "id", 12L);
            return v;
        });
    }

    private static void assertModelYearError(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(e.fieldErrors()).extracting("field").containsExactly(field);
        });
    }

    @Test
    @DisplayName("06 등록: 상한(현재 연도+1)은 통과, 그보다 크면 400이고 필드 이름은 vehicle.modelYear, 고객·차량 모두 저장하지 않는다")
    void registerUpperBound() {
        stubSaves();
        assertThatCode(() -> service().register(SHOP, registerRequest(2027))).doesNotThrowAnyException();

        assertModelYearError(() -> service().register(SHOP, registerRequest(2028)), "vehicle.modelYear");

        // 통과한 한 번만 저장되었고, 상한을 넘긴 요청은 고객도 저장하지 않았다
        verify(customers).save(any(Customer.class));
        verify(vehicles).save(any(Vehicle.class));
    }

    @Test
    @DisplayName("06 등록: 연식이 비어 있으면 상한 검사를 하지 않는다 (선택 입력)")
    void registerWithoutModelYear() {
        stubSaves();

        assertThatCode(() -> service().register(SHOP, registerRequest(null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("08 차량 추가: 상한은 통과, 그보다 크면 400이고 필드 이름은 modelYear(vehicle. 접두사 없음), 차량을 저장하지 않는다")
    void addVehicleUpperBound() {
        Customer owned = Customer.register(SHOP, "홍길동", "010-1234-5678", true);
        ReflectionTestUtils.setField(owned, "id", CUSTOMER);
        when(customers.findByIdAndShopId(CUSTOMER, SHOP)).thenReturn(Optional.of(owned));
        when(vehicles.save(any(Vehicle.class))).thenAnswer(inv -> {
            Vehicle v = inv.getArgument(0);
            ReflectionTestUtils.setField(v, "id", 12L);
            return v;
        });
        assertThatCode(() -> service().addVehicle(SHOP, CUSTOMER, vehicle(2027))).doesNotThrowAnyException();

        assertModelYearError(() -> service().addVehicle(SHOP, CUSTOMER, vehicle(2028)), "modelYear");

        verify(vehicles).save(any(Vehicle.class));
    }

    @Test
    @DisplayName("X17 08 차량 추가: 없거나 남의 정비소 고객이면 연식이 틀려도 404가 먼저이고 차량을 저장하지 않는다")
    void addVehicleNotOwnedCustomerIsNotFoundFirst() {
        when(customers.findByIdAndShopId(CUSTOMER, SHOP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addVehicle(SHOP, CUSTOMER, vehicle(2099)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(vehicles);
    }
}
