package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.customer.api.CustomerWriter;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.customer.entity.Vehicle;
import com.wanted.chagok.domain.customer.repository.CustomerRepository;
import com.wanted.chagok.domain.customer.repository.VehicleRepository;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-58 「차량 연식·주행거리가 비어 있으면 요청에 있어야 한다」의 한 곳({@code CustomerWriter.requireVehicleInfo})과,
 * 호출하는 API마다 다른 연식 오류 필드 이름(12번 {@code vehicle.modelYear}, 39번 {@code modelYear}) 단위 테스트.
 * Repository는 가짜(DB 없음). 오늘은 2026-11-25로 고정(연식 상한 2027).
 */
@ExtendWith(MockitoExtension.class)
class CustomerWriterRequireInfoTest {

    private static final long SHOP = 7L;
    private static final long VEHICLE = 3L;

    @Mock
    private CustomerRepository customers;

    @Mock
    private VehicleRepository vehicles;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static VehicleRef ref(Integer modelYear, Integer latestMileage) {
        return new VehicleRef(VEHICLE, 1L, "12가3456", "아반떼", modelYear, latestMileage);
    }

    @Test
    @DisplayName("둘 다 비어 있고 요청에도 없으면 400, 호출한 API의 필드 이름으로 둘 다 알린다")
    void bothMissing() {
        assertThatThrownBy(() -> CustomerWriter.requireVehicleInfo(ref(null, null), null, null, "modelYear"))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("modelYear", "mileage");
            });
        assertThatThrownBy(() -> CustomerWriter.requireVehicleInfo(ref(null, null), null, null, "vehicle.modelYear"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("vehicle.modelYear", "mileage"));
    }

    @Test
    @DisplayName("비어 있는 쪽만 요구한다 — 차량에 값이 있거나 요청에 값이 있으면 통과")
    void onlyMissingOnes() {
        assertThatThrownBy(() -> CustomerWriter.requireVehicleInfo(ref(null, 54_000), null, null, "modelYear"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("modelYear"));
        assertThatThrownBy(() -> CustomerWriter.requireVehicleInfo(ref(2018, null), null, null, "modelYear"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("mileage"));

        assertThatCode(() -> CustomerWriter.requireVehicleInfo(ref(2018, 54_000), null, null, "modelYear"))
            .doesNotThrowAnyException();
        assertThatCode(() -> CustomerWriter.requireVehicleInfo(ref(null, null), 2019, 41_000, "modelYear"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("applyVisit(필드 이름 지정): 연식 범위 오류가 호출한 API의 필드 이름으로 나가고(39번 modelYear), 4개 인자 호출은 vehicle.modelYear (12번)")
    void rangeErrorUsesCallersFieldName() {
        CustomerService service = new CustomerService(customers, vehicles, clock);
        when(vehicles.findForUpdateByIdAndShopId(VEHICLE, SHOP))
            .thenReturn(Optional.of(Vehicle.register(SHOP, 1L, "12가3456", "아반떼", null, null)));

        assertThatThrownBy(() -> service.applyVisit(SHOP, VEHICLE, 1979, null, "modelYear"))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("modelYear");
            });
        assertThatThrownBy(() -> service.applyVisit(SHOP, VEHICLE, 2028, null, "modelYear"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("modelYear"));
        assertThatThrownBy(() -> service.applyVisit(SHOP, VEHICLE, 1979, null))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("vehicle.modelYear"));
    }
}
