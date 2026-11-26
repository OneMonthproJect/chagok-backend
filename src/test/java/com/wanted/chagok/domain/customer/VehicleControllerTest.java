package com.wanted.chagok.domain.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.customer.controller.VehicleController;
import com.wanted.chagok.domain.customer.dto.CustomerCreateRequest;
import com.wanted.chagok.domain.customer.dto.VehicleAddResponse;
import com.wanted.chagok.domain.customer.dto.VehicleDetailResponse;
import com.wanted.chagok.domain.customer.service.CustomerService;
import com.wanted.chagok.global.auth.TestJwts;
import com.wanted.chagok.global.config.ClockConfig;
import com.wanted.chagok.global.config.JacksonConfig;
import com.wanted.chagok.global.config.SecurityConfig;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import com.wanted.chagok.global.error.GlobalExceptionHandler;
import java.time.Instant;
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
 * API 08·09 (08 4장): 실제 보안 규칙·검증·전역 처리기를 거친 요청/응답 모양. Service는 목(DB 없음).
 * 저장 정합성·격리(X17·X2)는 {@link VehicleServiceDbTest}.
 */
@WebMvcTest(VehicleController.class)
@Import({SecurityConfig.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "chagok.jwt.secret=" + VehicleControllerTest.SECRET)
class VehicleControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CustomerService customerService;

    private String token(long shopId, String role) {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(7, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    private ResultActions add(long customerId, String body) throws Exception {
        return mvc.perform(post("/api/customers/" + customerId + "/vehicles").header("Authorization", token(1, "OWNER"))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- 08 차량 추가 ----

    @Test
    @DisplayName("08: 토큰의 shopId·경로의 customerId로 저장하고 08 모양으로 응답")
    void add_usesTokenShopAndReturnsSpecShape() throws Exception {
        when(customerService.addVehicle(eq(1L), eq(10L), any(CustomerCreateRequest.VehicleInput.class)))
            .thenReturn(new VehicleAddResponse(10, 11));

        add(10, "{\"plateNumber\":\"66 카 0011\",\"model\":\"K3\",\"modelYear\":null,\"mileage\":null}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.customerId").value(10))
            .andExpect(jsonPath("$.data.vehicleId").value(11));

        ArgumentCaptor<CustomerCreateRequest.VehicleInput> input = ArgumentCaptor.forClass(CustomerCreateRequest.VehicleInput.class);
        verify(customerService).addVehicle(eq(1L), eq(10L), input.capture());
        org.assertj.core.api.Assertions.assertThat(input.getValue().plateNumber()).isEqualTo("66카0011");
        org.assertj.core.api.Assertions.assertThat(input.getValue().model()).isEqualTo("K3");
    }

    @Test
    @DisplayName("08 X8: 요청 본문의 shopId=2는 읽지 않고 토큰의 shopId(1)로 처리")
    void add_bodyShopIdIsIgnored() throws Exception {
        when(customerService.addVehicle(anyLong(), anyLong(), any(CustomerCreateRequest.VehicleInput.class)))
            .thenReturn(new VehicleAddResponse(10, 11));

        add(10, "{\"shopId\":2,\"plateNumber\":\"66카0011\",\"model\":\"K3\"}").andExpect(status().isOk());

        verify(customerService).addVehicle(eq(1L), eq(10L), any(CustomerCreateRequest.VehicleInput.class));
    }

    @Test
    @DisplayName("08: 차종 누락·빈 차종·주행거리 범위·연식 하한은 400 INVALID_INPUT이고 저장하지 않는다")
    void add_invalidInput() throws Exception {
        add(10, "{\"plateNumber\":\"66카0011\",\"modelYear\":2020}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("model"));
        add(10, "{\"plateNumber\":\"66카0011\",\"model\":\"   \"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("model"));
        add(10, "{\"plateNumber\":\"66카0011\",\"model\":\"K3\",\"mileage\":2000001}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("mileage"));
        add(10, "{\"plateNumber\":\"66카0011\",\"model\":\"K3\",\"modelYear\":1979}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("modelYear"));

        verify(customerService, never()).addVehicle(anyLong(), anyLong(), any(CustomerCreateRequest.VehicleInput.class));
    }

    @Test
    @DisplayName("08 X17: 없거나 남의 정비소 고객이면 404 NOT_FOUND")
    void add_notFound() throws Exception {
        when(customerService.addVehicle(anyLong(), anyLong(), any(CustomerCreateRequest.VehicleInput.class)))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        add(999, "{\"plateNumber\":\"66카0011\",\"model\":\"K3\"}")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- 09 차량 상세 ----

    @Test
    @DisplayName("09: 토큰의 shopId로 조회하고 08 모양(고객 연락처 전체 포함)으로 응답, 비어 있는 값은 null")
    void detail_returnsSpecShape() throws Exception {
        when(customerService.getVehicleDetail(1L, 4L)).thenReturn(new VehicleDetailResponse(4, "78라0004", "K5", 2020, 45_000,
            new VehicleDetailResponse.CustomerInfo(4, "최지은", "010-0000-0004", true)));
        when(customerService.getVehicleDetail(1L, 5L)).thenReturn(new VehicleDetailResponse(5, "12가0005", "K3", null, null,
            new VehicleDetailResponse.CustomerInfo(4, "최지은", "010-0000-0004", true)));

        mvc.perform(get("/api/vehicles/4").header("Authorization", token(1, "MECHANIC")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.vehicleId").value(4))
            .andExpect(jsonPath("$.data.plateNumber").value("78라0004"))
            .andExpect(jsonPath("$.data.model").value("K5"))
            .andExpect(jsonPath("$.data.modelYear").value(2020))
            .andExpect(jsonPath("$.data.latestMileage").value(45_000))
            .andExpect(jsonPath("$.data.customer.customerId").value(4))
            .andExpect(jsonPath("$.data.customer.name").value("최지은"))
            .andExpect(jsonPath("$.data.customer.phone").value("010-0000-0004"))
            .andExpect(jsonPath("$.data.customer.consent").value(true));
        mvc.perform(get("/api/vehicles/5").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.modelYear").isEmpty())
            .andExpect(jsonPath("$.data.latestMileage").isEmpty());
    }

    @Test
    @DisplayName("09 X2: 없거나 남의 정비소 차량이면 404 NOT_FOUND")
    void detail_notFound() throws Exception {
        when(customerService.getVehicleDetail(anyLong(), anyLong())).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mvc.perform(get("/api/vehicles/999").header("Authorization", token(1, "OWNER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("토큰 없음 401, 서비스 관리자 토큰 403 (X9·X10)")
    void authRules() throws Exception {
        mvc.perform(get("/api/vehicles/4")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/customers/10/vehicles").contentType(MediaType.APPLICATION_JSON)
            .content("{\"plateNumber\":\"66카0011\",\"model\":\"K3\"}")).andExpect(status().isUnauthorized());

        String admin = "Bearer " + TestJwts.sign(SECRET, TestJwts.admin(1, Instant.now().plusSeconds(3_600)));
        mvc.perform(get("/api/vehicles/4").header("Authorization", admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/customers/10/vehicles").header("Authorization", admin)
            .contentType(MediaType.APPLICATION_JSON).content("{\"plateNumber\":\"66카0011\",\"model\":\"K3\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("08·09는 사장·정비사 모두 가능: 정비사(MECHANIC) 토큰으로 차량 추가 200, 서비스는 토큰의 shopId로 호출")
    void mechanicCanAddVehicle() throws Exception {
        when(customerService.addVehicle(eq(1L), eq(10L), any(CustomerCreateRequest.VehicleInput.class)))
            .thenReturn(new VehicleAddResponse(10, 11));

        mvc.perform(post("/api/customers/10/vehicles").header("Authorization", token(1, "MECHANIC"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plateNumber\":\"66카0011\",\"model\":\"K3\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.vehicleId").value(11));
    }
}
