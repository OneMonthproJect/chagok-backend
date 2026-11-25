package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanted.chagok.domain.customer.entity.Vehicle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code Vehicle.applyVisit}: 연식은 비어 있을 때만, 최근 주행거리는 비어 있거나 더 클 때만 바뀐다 (04 F-MNT-01, 05 POL-60). DB 없음. */
class VehicleApplyVisitTest {

    private static Vehicle vehicle(Integer year, Integer mileage) {
        return Vehicle.register(1L, 1L, "12가3456", "아반떼", year, mileage);
    }

    @Test
    @DisplayName("연식·주행거리가 비어 있으면 요청 값으로 채운다")
    void fillsEmptyValues() {
        Vehicle v = vehicle(null, null);

        v.applyVisit(2019, 55_000);

        assertThat(v.getModelYear()).isEqualTo(2019);
        assertThat(v.getLatestMileage()).isEqualTo(55_000);
    }

    @Test
    @DisplayName("연식이 이미 있으면 요청 값이 와도 바꾸지 않는다")
    void keepsExistingModelYear() {
        Vehicle v = vehicle(2018, 10_000);

        v.applyVisit(2021, null);

        assertThat(v.getModelYear()).isEqualTo(2018);
    }

    @Test
    @DisplayName("주행거리는 더 클 때만 갱신하고, 같거나 작으면 그대로 둔다")
    void mileageOnlyIncreases() {
        Vehicle v = vehicle(2018, 50_000);

        v.applyVisit(null, 60_000);
        assertThat(v.getLatestMileage()).isEqualTo(60_000);

        v.applyVisit(null, 60_000);
        assertThat(v.getLatestMileage()).isEqualTo(60_000);

        v.applyVisit(null, 1_000);
        assertThat(v.getLatestMileage()).isEqualTo(60_000);
    }

    @Test
    @DisplayName("주행거리 0은 비어 있는 차량에 채워지고, 요청이 null이면 아무것도 바뀌지 않는다")
    void zeroMileageAndNulls() {
        Vehicle empty = vehicle(null, null);
        empty.applyVisit(null, 0);
        assertThat(empty.getLatestMileage()).isZero();
        assertThat(empty.getModelYear()).isNull();

        Vehicle v = vehicle(2018, 50_000);
        v.applyVisit(null, null);
        assertThat(v.getModelYear()).isEqualTo(2018);
        assertThat(v.getLatestMileage()).isEqualTo(50_000);
    }
}
