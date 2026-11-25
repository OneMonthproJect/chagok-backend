package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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
 * {@code CustomerWriter.applyVisit} 단위 테스트 — Repository는 가짜(DB 없음). 소속 404, 범위 400, 반영 규칙을 본다.
 * 실제 저장·정비소 격리 쿼리는 {@code CustomerWriterDbTest}(dbTest)에서 본다. 오늘은 2026-11-25로 고정(연식 상한 2027).
 */
@ExtendWith(MockitoExtension.class)
class CustomerWriterTest {

    private static final long SHOP = 7L;
    private static final long VEHICLE = 3L;

    @Mock
    private CustomerRepository customers;

    @Mock
    private VehicleRepository vehicles;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private CustomerService service() {
        return new CustomerService(customers, vehicles, clock, org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class));
    }

    private Vehicle owned(Integer year, Integer mileage) {
        Vehicle v = Vehicle.register(SHOP, 1L, "12가3456", "아반떼", year, mileage);
        when(vehicles.findForUpdateByIdAndShopId(VEHICLE, SHOP)).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    @DisplayName("비어 있는 차량에 연식·주행거리를 채운다")
    void fillsEmptyVehicle() {
        Vehicle v = owned(null, null);

        service().applyVisit(SHOP, VEHICLE, 2019, 55_000);

        assertThat(v.getModelYear()).isEqualTo(2019);
        assertThat(v.getLatestMileage()).isEqualTo(55_000);
    }

    @Test
    @DisplayName("더 작은 주행거리는 오류 없이 무시하고, 이미 있는 연식도 바꾸지 않는다")
    void ignoresSmallerMileageAndExistingYear() {
        Vehicle v = owned(2018, 80_000);

        service().applyVisit(SHOP, VEHICLE, 2021, 10_000);

        assertThat(v.getModelYear()).isEqualTo(2018);
        assertThat(v.getLatestMileage()).isEqualTo(80_000);
    }

    @Test
    @DisplayName("없거나 남의 정비소 차량이면 404 NOT_FOUND")
    void notOwnedVehicle() {
        when(vehicles.findForUpdateByIdAndShopId(VEHICLE, SHOP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().applyVisit(SHOP, VEHICLE, 2019, 1_000))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("연식 범위(1980 ~ 올해+1) 밖이면 400이고 필드는 vehicle.modelYear, 경계값은 통과")
    void modelYearRange() {
        Vehicle v = owned(null, null);
        service().applyVisit(SHOP, VEHICLE, 2027, null);
        assertThat(v.getModelYear()).isEqualTo(2027);

        for (int bad : new int[] {1979, 2028}) {
            owned(null, null);
            assertThatThrownBy(() -> service().applyVisit(SHOP, VEHICLE, bad, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("vehicle.modelYear");
                });
        }
    }

    @Test
    @DisplayName("주행거리 범위(0 ~ 2,000,000) 밖이면 400이고 필드는 mileage, 경계값은 통과")
    void mileageRange() {
        Vehicle v = owned(2018, null);
        service().applyVisit(SHOP, VEHICLE, null, 2_000_000);
        assertThat(v.getLatestMileage()).isEqualTo(2_000_000);

        for (int bad : new int[] {-1, 2_000_001}) {
            owned(2018, null);
            assertThatThrownBy(() -> service().applyVisit(SHOP, VEHICLE, null, bad))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("mileage");
                });
        }
    }
}
