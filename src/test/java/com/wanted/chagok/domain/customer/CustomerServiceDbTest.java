package com.wanted.chagok.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.customer.api.CustomerReader;
import com.wanted.chagok.domain.customer.api.VehicleRef;
import com.wanted.chagok.domain.customer.api.VehicleSummary;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest.VehicleInput;
import com.wanted.chagok.domain.customer.dto.CustomerCreateResponse;
import com.wanted.chagok.domain.customer.dto.CustomerSearchResponse;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.web.PageLimit;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 저장 정합성·검색·정비소 격리 (X1·X2, Entity ↔ V1 {@code ddl-auto: validate}). 앱 전체가 뜨므로 빈 MySQL이 필요하다
 * ({@code ./gradlew dbTest}, CI db-test). 테스트 트랜잭션은 끝나면 롤백한다. 정비소 두 곳은 이 테스트가 직접 넣는다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class CustomerServiceDbTest {

    @Autowired
    private CustomerService service;

    @Autowired
    private CustomerReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long shopA;
    private long shopB;

    @BeforeEach
    void shops() {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES ('A정비소', '02-000-0001', 'ACTIVE', NOW())");
        shopA = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES ('B정비소', '02-000-0002', 'ACTIVE', NOW())");
        shopB = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static CustomerCreateRequest request(String name, String phone, String plate, Integer year, Integer mileage) {
        return new CustomerCreateRequest(name, phone, false, new VehicleInput(plate, "아반떼", year, mileage));
    }

    private CustomerCreateResponse register(long shopId, String name, String phone, String plate) {
        return service.register(shopId, request(name, phone, plate, 2019, null));
    }

    private CustomerSearchResponse search(long shopId, String q) {
        return service.search(shopId, q, PageLimit.of(null, null));
    }

    @Test
    @DisplayName("등록: 고객·차량이 함께 저장되고(Entity validate·타임스탬프 포함) 차량 값이 그대로 읽힌다")
    void register_savesCustomerAndVehicle() {
        CustomerCreateResponse created = service.register(shopA, request("정우성", "010-0000-0005", "90 마 0005", 2019, 12_000));

        VehicleRef vehicle = reader.getOwnedVehicle(shopA, created.vehicleId());
        assertThat(vehicle.customerId()).isEqualTo(created.customerId());
        assertThat(vehicle.plateNumber()).isEqualTo("90마0005");
        assertThat(vehicle.model()).isEqualTo("아반떼");
        assertThat(vehicle.modelYear()).isEqualTo(2019);
        assertThat(vehicle.latestMileage()).isEqualTo(12_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE shop_id = ? AND consent = FALSE", Integer.class, shopA))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE created_at IS NOT NULL AND updated_at IS NOT NULL",
            Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("등록: 연식·주행거리가 비어 있어도 저장된다(null), 차량번호 중복은 막지 않는다")
    void register_optionalFieldsAndDuplicatePlate() {
        CustomerCreateResponse first = service.register(shopA, request("가", "010-1111-1111", "12가3456", null, null));
        CustomerCreateResponse second = register(shopA, "나", "010-2222-2222", "12가3456");

        VehicleRef vehicle = reader.getOwnedVehicle(shopA, first.vehicleId());
        assertThat(vehicle.modelYear()).isNull();
        assertThat(vehicle.latestMileage()).isNull();
        assertThat(second.vehicleId()).isNotEqualTo(first.vehicleId());
        assertThat(search(shopA, "12가3456").items()).hasSize(2);
    }

    @Test
    @DisplayName("연식 상한: 현재 연도+1은 통과, +2는 400 INVALID_INPUT(vehicle.modelYear), 저장 안 됨")
    void register_modelYearUpperBound() {
        int nextYear = LocalDate.now(clock).getYear() + 1;
        service.register(shopA, request("가", "010-1111-1111", "1가1111", nextYear, null));

        assertThatThrownBy(() -> service.register(shopA, request("나", "010-2222-2222", "2나2222", nextYear + 1, null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.fieldErrors()).extracting("field").containsExactly("vehicle.modelYear");
            });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("검색: 고객명·연락처(하이픈 무시)·차량번호(공백 무시) 일부, 비우면 전체 최근 등록순")
    void search_byNamePhonePlate() {
        register(shopA, "김민수", "010-0000-0001", "12가0001");
        register(shopA, "이서연", "010-0000-0002", "34나0002");

        assertThat(search(shopA, "민수").items()).extracting("name").containsExactly("김민수");
        assertThat(search(shopA, "0000-0002").items()).extracting("name").containsExactly("이서연");
        assertThat(search(shopA, "01000000002").items()).extracting("name").containsExactly("이서연");
        assertThat(search(shopA, "34 나").items()).extracting("name").containsExactly("이서연");
        assertThat(search(shopA, "없는검색어").items()).isEmpty();
        assertThat(search(shopA, "없는검색어").totalElements()).isZero();
        assertThat(search(shopA, null).items()).extracting("name").containsExactly("이서연", "김민수");
        assertThat(search(shopA, "  ").items()).hasSize(2);
        assertThat(search(shopA, "12가0001").items().get(0).vehicles())
            .extracting("plateNumber").containsExactly("12가0001");
    }

    @Test
    @DisplayName("검색 F-CUS-07: 응답의 연락처는 마스킹되고(목록), 검색은 전체 번호로 되며 저장된 값은 그대로다")
    void search_masksPhoneInResponseOnly() {
        long id = register(shopA, "김민수", "010-0000-0001", "12가0001").customerId();

        assertThat(search(shopA, "010-0000-0001").items()).extracting("phone").containsExactly("010-****-0001");
        assertThat(search(shopA, "민수").items()).extracting("phone").containsExactly("010-****-0001");
        assertThat(jdbc.queryForObject("SELECT phone FROM customer WHERE id = ?", String.class, id)).isEqualTo("010-0000-0001");
    }

    @Test
    @DisplayName("검색: % · _ 는 글자 그대로 찾는다(전체 일치로 번지지 않음)")
    void search_likeWildcardsAreLiteral() {
        register(shopA, "김민수", "010-0000-0001", "12가0001");

        assertThat(search(shopA, "%").items()).isEmpty();
        assertThat(search(shopA, "_").items()).isEmpty();
    }

    @Test
    @DisplayName("쪽 나누기: size=2면 2건씩, 총 건수·쪽 수 계산, 상한 50")
    void search_paging() {
        for (int i = 1; i <= 5; i++) {
            register(shopA, "고객" + i, "010-0000-000" + i, "1가000" + i);
        }
        CustomerSearchResponse p0 = service.search(shopA, null, PageLimit.of(0, 2));
        CustomerSearchResponse p2 = service.search(shopA, null, PageLimit.of(2, 2));

        assertThat(p0.items()).extracting("name").containsExactly("고객5", "고객4");
        assertThat(p0.totalElements()).isEqualTo(5);
        assertThat(p0.totalPages()).isEqualTo(3);
        assertThat(p2.items()).extracting("name").containsExactly("고객1");
        assertThat(service.search(shopA, null, PageLimit.of(0, 999)).size()).isEqualTo(50);
    }

    @Test
    @DisplayName("X1·X2 격리: B 정비소 고객은 A 검색에 섞이지 않고, A 토큰으로 B 차량·고객 조회는 404")
    void isolation_betweenShops() {
        CustomerCreateResponse inA = register(shopA, "김민수", "010-0000-0001", "12가0001");
        CustomerCreateResponse inB = register(shopB, "김민수", "010-0000-0001", "12가0001");

        assertThat(search(shopA, "김민수").items()).extracting("customerId").containsExactly(inA.customerId());
        assertThat(search(shopB, "").items()).extracting("customerId").containsExactly(inB.customerId());
        assertThat(search(shopA, "").totalElements()).isEqualTo(1);

        assertThatThrownBy(() -> reader.getOwnedVehicle(shopA, inB.vehicleId()))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.getOwned(shopA, inB.customerId()))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> reader.getOwnedVehicle(shopA, 999_999L))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("계약 findVehicleSummaries: 한 번에 여러 대, 남의 정비소·없는 차량은 결과에 없음, 빈 입력은 빈 Map")
    void reader_vehicleSummaries() {
        CustomerCreateResponse a = register(shopA, "김민수", "010-0000-0001", "12가0001");
        CustomerCreateResponse b = register(shopA, "이서연", "010-0000-0002", "34나0002");
        CustomerCreateResponse other = register(shopB, "박타사", "010-0000-0003", "56다0003");

        Map<Long, VehicleSummary> result = reader.findVehicleSummaries(shopA,
            List.of(a.vehicleId(), b.vehicleId(), other.vehicleId(), 999_999L));

        assertThat(result).containsOnlyKeys(a.vehicleId(), b.vehicleId());
        assertThat(result.get(a.vehicleId())).isEqualTo(
            new VehicleSummary(a.vehicleId(), "12가0001", a.customerId(), "김민수", "010-0000-0001", false));
        assertThat(reader.findVehicleSummaries(shopA, List.of())).isEmpty();
    }

    @Test
    @DisplayName("계약 findConsentedVehicles: 동의 고객의 차량만(미동의 제외), 한 고객의 차량이 여럿이면 모두, id 오름차순, 다른 정비소는 섞이지 않는다")
    void reader_consentedVehicles() {
        register(shopA, "김미동의", "010-0000-0001", "12가0001");
        CustomerCreateResponse agreed = service.register(shopA, new CustomerCreateRequest("이동의", "010-0000-0002", true,
            new VehicleInput("34나0002", "쏘나타", 2018, null)));
        CustomerCreateResponse agreed2 = service.register(shopA, new CustomerCreateRequest("박동의", "010-0000-0003", true,
            new VehicleInput("78라0004", "K5", 2020, null)));
        // 이동의의 둘째 차량을 박동의 등록 뒤에 추가한다 — 고객 순서와 차량 id 순서가 어긋나야 정렬(order by v.id)이 증명된다
        service.addVehicle(shopA, agreed.customerId(), new VehicleInput("56다0003", "그랜저", 2017, null));
        service.register(shopB, new CustomerCreateRequest("최타사", "010-0000-0004", true,
            new VehicleInput("90마0005", "투싼", 2016, null)));

        List<VehicleSummary> result = reader.findConsentedVehicles(shopA);

        // 미동의(12가0001)와 다른 정비소(90마0005)는 없고, 동의 고객의 차량 3대가 고객이 아니라 차량 id 순으로 나온다
        assertThat(result).extracting(VehicleSummary::plateNumber).containsExactly("34나0002", "78라0004", "56다0003");
        assertThat(result).extracting(VehicleSummary::vehicleId).isSorted();
        assertThat(result).allSatisfy(v -> assertThat(v.consent()).isTrue());
        assertThat(result).extracting(VehicleSummary::customerId)
            .containsExactly(agreed.customerId(), agreed2.customerId(), agreed.customerId());
        assertThat(result.get(0).customerName()).isEqualTo("이동의");
        assertThat(result.get(0).phone()).isEqualTo("010-0000-0002");
    }

    @Test
    @DisplayName("계약 findConsentedVehicles: 동의 고객이 없는 정비소는 빈 목록, 다른 정비소의 동의 고객은 보이지 않는다")
    void reader_consentedVehiclesEmptyAndIsolation() {
        register(shopA, "김미동의", "010-0000-0001", "12가0001");
        service.register(shopB, new CustomerCreateRequest("최타사", "010-0000-0004", true,
            new VehicleInput("90마0005", "투싼", 2016, null)));

        assertThat(reader.findConsentedVehicles(shopA)).isEmpty();
        assertThat(reader.findConsentedVehicles(shopB)).extracting(VehicleSummary::plateNumber).containsExactly("90마0005");
    }

    @Test
    @DisplayName("계약 findVehicleSummaries의 consent: 등록 때 정한 동의 여부(true·false)가 그대로 읽힌다 (08 17번 customer.consent)")
    void reader_vehicleSummariesConsent() {
        CustomerCreateResponse declined = register(shopA, "김민수", "010-0000-0001", "12가0001");
        CustomerCreateResponse agreed = service.register(shopA, new CustomerCreateRequest("이서연", "010-0000-0002", true,
            new VehicleInput("34나0002", "쏘나타", 2018, null)));

        Map<Long, VehicleSummary> result = reader.findVehicleSummaries(shopA,
            List.of(declined.vehicleId(), agreed.vehicleId()));

        assertThat(result.get(declined.vehicleId()).consent()).isFalse();
        assertThat(result.get(agreed.vehicleId()).consent()).isTrue();
    }
}
