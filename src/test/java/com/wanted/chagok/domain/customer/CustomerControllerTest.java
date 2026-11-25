package com.wanted.chagok.domain.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.customer.controller.CustomerController;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerCreateResponse;
import com.wanted.chagok.domain.customer.dto.CustomerSearchResponse;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.JacksonConfig;
import com.wanted.chagok.global.config.SecurityConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import com.wanted.chagok.global.web.PageLimit;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * API 05·06 (08 4장): 실제 보안 규칙·검증·전역 처리기를 거친 요청/응답 모양. Service는 목(DB 없음).
 * 저장 정합성·격리(X1·X2)는 {@link CustomerServiceDbTest}.
 */
@WebMvcTest(CustomerController.class)
@Import({SecurityConfig.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "chagok.jwt.secret=" + CustomerControllerTest.SECRET)
class CustomerControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CustomerService customerService;

    private String token(long shopId, String role) {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(7, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    private static final String VALID_BODY = """
        {"name":"정우성","phone":"010-0000-0005","consent":true,
         "vehicle":{"plateNumber":"90 마 0005","model":"아반떼","modelYear":2019,"mileage":null}}""";

    private ResultActions register(String body) throws Exception {
        return mvc.perform(post("/api/customers").header("Authorization", token(1, "OWNER"))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String bodyWithVehicle(String vehicleJson) {
        return """
            {"name":"정우성","phone":"010-0000-0005","consent":false,"vehicle":%s}""".formatted(vehicleJson);
    }

    // ---- 05 검색 ----

    @Test
    @DisplayName("05 검색: 토큰의 shopId로 조회하고 08 모양으로 응답")
    void search_usesTokenShopAndReturnsSpecShape() throws Exception {
        when(customerService.search(eq(1L), eq("0001"), any(PageLimit.class)))
            .thenReturn(new CustomerSearchResponse(
                List.of(new CustomerSearchResponse.Item(1, "김민수", "010-0000-0001", true,
                    List.of(new CustomerSearchResponse.VehicleItem(1, "12가0001")))), 0, 20, 1, 1));

        mvc.perform(get("/api/customers").param("q", "0001").header("Authorization", token(1, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.items[0].customerId").value(1))
            .andExpect(jsonPath("$.data.items[0].phone").value("010-0000-0001"))
            .andExpect(jsonPath("$.data.items[0].consent").value(true))
            .andExpect(jsonPath("$.data.items[0].vehicles[0].plateNumber").value("12가0001"))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    @DisplayName("X8 요청 쿼리의 shopId=2는 무시하고 토큰의 shopId(1)로 처리")
    void x8_queryShopIdIsIgnored() throws Exception {
        when(customerService.search(anyLong(), any(), any(PageLimit.class)))
            .thenReturn(new CustomerSearchResponse(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/customers").param("shopId", "2").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isOk());

        verify(customerService).search(eq(1L), any(), any(PageLimit.class));
    }

    @Test
    @DisplayName("쪽 크기는 최대 50으로 자른다 (size=999 → 50)")
    void search_sizeIsCappedAt50() throws Exception {
        when(customerService.search(anyLong(), any(), any(PageLimit.class)))
            .thenReturn(new CustomerSearchResponse(List.of(), 0, 50, 0, 0));

        mvc.perform(get("/api/customers").param("size", "999").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isOk());

        ArgumentCaptor<PageLimit> limit = ArgumentCaptor.forClass(PageLimit.class);
        verify(customerService).search(eq(1L), any(), limit.capture());
        org.assertj.core.api.Assertions.assertThat(limit.getValue()).isEqualTo(new PageLimit(0, 50));
    }

    @Test
    @DisplayName("쪽 번호 음수·숫자 아닌 size → 400 INVALID_INPUT")
    void search_badPaging() throws Exception {
        mvc.perform(get("/api/customers").param("page", "-1").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("page"));
        mvc.perform(get("/api/customers").param("size", "abc").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("토큰 없음 401, 서비스 관리자 토큰 403 (X9·X10)")
    void search_authRules() throws Exception {
        mvc.perform(get("/api/customers")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        String admin = "Bearer " + TestJwts.sign(SECRET, TestJwts.admin(1, Instant.now().plusSeconds(3_600)));
        mvc.perform(get("/api/customers").header("Authorization", admin)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ---- 06 등록 ----

    @Test
    @DisplayName("06 등록: 사장·정비사 모두 가능, 차량번호 공백 제거, 토큰의 shopId로 저장, 응답 {customerId, vehicleId}")
    void register_success() throws Exception {
        when(customerService.register(anyLong(), any(CustomerCreateRequest.class)))
            .thenReturn(new CustomerCreateResponse(5, 5));

        register(VALID_BODY)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.customerId").value(5))
            .andExpect(jsonPath("$.data.vehicleId").value(5))
            .andExpect(jsonPath("$.error").isEmpty());

        ArgumentCaptor<CustomerCreateRequest> captor = ArgumentCaptor.forClass(CustomerCreateRequest.class);
        verify(customerService).register(eq(1L), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().vehicle().plateNumber()).isEqualTo("90마0005");

        mvc.perform(post("/api/customers").header("Authorization", token(1, "MECHANIC"))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("X8 요청 본문의 shopId=2는 무시하고 토큰의 shopId(1)로 저장")
    void x8_bodyShopIdIsIgnored() throws Exception {
        when(customerService.register(anyLong(), any(CustomerCreateRequest.class)))
            .thenReturn(new CustomerCreateResponse(5, 5));
        String body = """
            {"shopId":2,"name":"정우성","phone":"010-0000-0005","consent":true,
             "vehicle":{"shopId":2,"plateNumber":"90마0005","model":"아반떼"}}""";

        register(body).andExpect(status().isOk());

        verify(customerService).register(eq(1L), any(CustomerCreateRequest.class));
    }

    @Test
    @DisplayName("차종(model) 누락·공백 → 400 INVALID_INPUT, fieldErrors에 vehicle.model (D-58)")
    void register_modelRequired() throws Exception {
        register(bodyWithVehicle("{\"plateNumber\":\"90마0005\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("vehicle.model"));
        register(bodyWithVehicle("{\"plateNumber\":\"90마0005\",\"model\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("vehicle.model"));
    }

    @Test
    @DisplayName("필수값 누락(이름·연락처·동의·차량) → 400")
    void register_requiredFields() throws Exception {
        register("{}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors.length()").value(4));
        register(bodyWithVehicle("{\"plateNumber\":\"   \",\"model\":\"아반떼\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("vehicle.plateNumber"));
    }

    @Test
    @DisplayName("연락처: 숫자·하이픈만, 9~20자")
    void register_phoneRules() throws Exception {
        String tpl = "{\"name\":\"가\",\"phone\":\"%s\",\"consent\":true,\"vehicle\":{\"plateNumber\":\"1가1111\",\"model\":\"A\"}}";
        register(tpl.formatted("010 0000 0005")).andExpect(status().isBadRequest());
        register(tpl.formatted("abc-1234-5678")).andExpect(status().isBadRequest());
        register(tpl.formatted("12345678")).andExpect(status().isBadRequest());
        register(tpl.formatted("1".repeat(21))).andExpect(status().isBadRequest());
        when(customerService.register(anyLong(), any(CustomerCreateRequest.class)))
            .thenReturn(new CustomerCreateResponse(1, 1));
        register(tpl.formatted("123456789")).andExpect(status().isOk());
        register(tpl.formatted("1".repeat(20))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("길이 상한: 이름 50자, 차량번호 20자(공백 제거 후), 차종 50자")
    void register_lengthLimits() throws Exception {
        when(customerService.register(anyLong(), any(CustomerCreateRequest.class)))
            .thenReturn(new CustomerCreateResponse(1, 1));
        String tpl = "{\"name\":\"%s\",\"phone\":\"010-0000-0005\",\"consent\":true,\"vehicle\":{\"plateNumber\":\"%s\",\"model\":\"%s\"}}";
        register(tpl.formatted("가".repeat(51), "1가1111", "A")).andExpect(status().isBadRequest());
        register(tpl.formatted("가".repeat(50), "1가1111", "A")).andExpect(status().isOk());
        register(tpl.formatted("가", "1".repeat(21), "A")).andExpect(status().isBadRequest());
        register(tpl.formatted("가", "1".repeat(20), "A")).andExpect(status().isOk());
        register(tpl.formatted("가", "1 ".repeat(30), "A")).andExpect(status().isBadRequest());
        register(tpl.formatted("가", "1가1111", "A".repeat(51))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("주행거리 경계: -1 · 2,000,001 → 400, 0 · 2,000,000 → 통과")
    void register_mileageBoundary() throws Exception {
        when(customerService.register(anyLong(), any(CustomerCreateRequest.class)))
            .thenReturn(new CustomerCreateResponse(1, 1));
        String tpl = "{\"plateNumber\":\"1가1111\",\"model\":\"A\",\"mileage\":%s}";
        register(bodyWithVehicle(tpl.formatted("-1"))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("vehicle.mileage"));
        register(bodyWithVehicle(tpl.formatted("2000001"))).andExpect(status().isBadRequest());
        register(bodyWithVehicle(tpl.formatted("0"))).andExpect(status().isOk());
        register(bodyWithVehicle(tpl.formatted("2000000"))).andExpect(status().isOk());
        register(bodyWithVehicle(tpl.formatted("\"abc\""))).andExpect(status().isBadRequest());
        // 정수가 아닌 값은 400 (08 0.1). 소수가 잘려서 저장되면 안 된다
        register(bodyWithVehicle(tpl.formatted("1.5"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("연식 하한: 1979 → 400, 1980 → 통과 (상한 현재 연도+1은 서비스, ServiceDbTest)")
    void register_modelYearLowerBound() throws Exception {
        when(customerService.register(anyLong(), any(CustomerCreateRequest.class)))
            .thenReturn(new CustomerCreateResponse(1, 1));
        String tpl = "{\"plateNumber\":\"1가1111\",\"model\":\"A\",\"modelYear\":%s}";
        register(bodyWithVehicle(tpl.formatted("1979"))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("vehicle.modelYear"));
        register(bodyWithVehicle(tpl.formatted("1980"))).andExpect(status().isOk());
        register(bodyWithVehicle(tpl.formatted("2019.5"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("서비스가 던진 400(연식 상한 초과)도 08 오류 모양으로 응답")
    void register_serviceInvalidFieldIsMapped() throws Exception {
        when(customerService.register(anyLong(), any(CustomerCreateRequest.class)))
            .thenThrow(BusinessException.invalidField("vehicle.modelYear", "범위 밖"));

        register(VALID_BODY).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_INPUT.name()))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("vehicle.modelYear"));
    }

    @Test
    @DisplayName("잘못된 JSON → 400, 토큰 없음 → 401")
    void register_badJsonAndAuth() throws Exception {
        register("{not json").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
            .andExpect(status().isUnauthorized());
    }
}
