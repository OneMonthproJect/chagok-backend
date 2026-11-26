package com.wanted.chagok.domain.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.domain.customer.controller.CustomerController;
import com.wanted.chagok.domain.customer.controller.VehicleController;
import com.wanted.chagok.domain.customer.dto.CustomerUpdateRequest;
import com.wanted.chagok.domain.customer.dto.CustomerUpdateResponse;
import com.wanted.chagok.domain.customer.dto.VehicleUpdateRequest;
import com.wanted.chagok.domain.customer.dto.VehicleUpdateResponse;
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
 * API 22·23 (08 7장): 실제 보안 규칙·검증·전역 처리기를 거친 요청/응답 모양. Service는 목(DB 없음).
 * {@code version} 비교·저장·격리(X12)는 {@link CustomerVehicleUpdateDbTest}.
 */
@WebMvcTest({CustomerController.class, VehicleController.class})
@Import({SecurityConfig.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "chagok.jwt.secret=" + CustomerVehicleUpdateControllerTest.SECRET)
class CustomerVehicleUpdateControllerTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CustomerService customerService;

    private String token(long shopId, String role) {
        return "Bearer " + TestJwts.sign(SECRET, TestJwts.staff(7, shopId, role, Instant.now().plusSeconds(3_600)));
    }

    private ResultActions patchCustomer(String body) throws Exception {
        return mvc.perform(patch("/api/customers/1").header("Authorization", token(1, "OWNER"))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions patchVehicle(String body) throws Exception {
        return mvc.perform(patch("/api/vehicles/4").header("Authorization", token(1, "OWNER"))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- 22 고객 수정 ----

    @Test
    @DisplayName("22: 토큰의 shopId·경로의 id·보낸 필드로 수정하고 08 예시 모양(바뀐 version 포함)으로 응답")
    void customer_returnsSpecShape() throws Exception {
        when(customerService.updateCustomer(eq(1L), eq(1L), any(CustomerUpdateRequest.class)))
            .thenReturn(new CustomerUpdateResponse(1, "김민수", "010-0000-0001", false, 1));

        patchCustomer("{\"version\":0,\"consent\":false}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.customerId").value(1))
            .andExpect(jsonPath("$.data.name").value("김민수"))
            .andExpect(jsonPath("$.data.phone").value("010-0000-0001"))
            .andExpect(jsonPath("$.data.consent").value(false))
            .andExpect(jsonPath("$.data.version").value(1));

        ArgumentCaptor<CustomerUpdateRequest> sent = ArgumentCaptor.forClass(CustomerUpdateRequest.class);
        verify(customerService).updateCustomer(eq(1L), eq(1L), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().version()).isZero();
        org.assertj.core.api.Assertions.assertThat(sent.getValue().name()).isNull();
        org.assertj.core.api.Assertions.assertThat(sent.getValue().consent()).isFalse();
    }

    @Test
    @DisplayName("22: version이 없거나 숫자가 아니면 400 INVALID_INPUT, Service를 부르지 않는다")
    void customer_versionRequired() throws Exception {
        patchCustomer("{\"consent\":false}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("version"));
        patchCustomer("{\"version\":null,\"consent\":false}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        patchCustomer("{\"version\":\"abc\",\"consent\":false}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verifyNoInteractions(customerService);
    }

    @Test
    @DisplayName("22: 보낸 값이 비었거나 형식이 틀리면 400 + 그 필드 (이름 비움·연락처 형식·길이)")
    void customer_invalidFields() throws Exception {
        patchCustomer("{\"version\":0,\"name\":\"   \"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"));
        patchCustomer("{\"version\":0,\"name\":\"" + "가".repeat(51) + "\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"));
        patchCustomer("{\"version\":0,\"phone\":\"abc-1234-5678\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("phone"));
        patchCustomer("{\"version\":0,\"phone\":\"010\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("phone"));
        verifyNoInteractions(customerService);
    }

    @Test
    @DisplayName("22: Service의 409 CONCURRENT_MODIFICATION·404 NOT_FOUND를 그대로 응답")
    void customer_passesThroughServiceErrors() throws Exception {
        when(customerService.updateCustomer(eq(1L), eq(1L), any(CustomerUpdateRequest.class)))
            .thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        patchCustomer("{\"version\":0,\"consent\":false}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        when(customerService.updateCustomer(eq(1L), eq(1L), any(CustomerUpdateRequest.class)))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        patchCustomer("{\"version\":0,\"consent\":false}").andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("22: 토큰 없으면 401, 서비스 관리자는 403, 사장·정비사는 허용")
    void customer_roles() throws Exception {
        String admin = "Bearer " + TestJwts.sign(SECRET, TestJwts.admin(1, Instant.now().plusSeconds(3_600)));
        mvc.perform(patch("/api/customers/1").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/customers/1").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":0}")).andExpect(status().isForbidden());
        verifyNoInteractions(customerService);

        when(customerService.updateCustomer(eq(1L), eq(1L), any(CustomerUpdateRequest.class)))
            .thenReturn(new CustomerUpdateResponse(1, "김민수", "010-0000-0001", true, 0));
        mvc.perform(patch("/api/customers/1").header("Authorization", token(1, "MECHANIC"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isOk());
    }

    // ---- 23 차량 수정 ----

    @Test
    @DisplayName("23: 토큰의 shopId·경로의 id·보낸 필드로 수정하고 08 예시 모양(바뀐 version 포함)으로 응답")
    void vehicle_returnsSpecShape() throws Exception {
        when(customerService.updateVehicle(eq(1L), eq(4L), any(VehicleUpdateRequest.class)))
            .thenReturn(new VehicleUpdateResponse(4, "78라0004", "K5 하이브리드", 2020, 45_000, 1));

        patchVehicle("{\"version\":0,\"model\":\"K5 하이브리드\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty())
            .andExpect(jsonPath("$.data.vehicleId").value(4))
            .andExpect(jsonPath("$.data.plateNumber").value("78라0004"))
            .andExpect(jsonPath("$.data.model").value("K5 하이브리드"))
            .andExpect(jsonPath("$.data.modelYear").value(2020))
            .andExpect(jsonPath("$.data.latestMileage").value(45_000))
            .andExpect(jsonPath("$.data.version").value(1));

        ArgumentCaptor<VehicleUpdateRequest> sent = ArgumentCaptor.forClass(VehicleUpdateRequest.class);
        verify(customerService).updateVehicle(eq(1L), eq(4L), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().model()).isEqualTo("K5 하이브리드");
        org.assertj.core.api.Assertions.assertThat(sent.getValue().plateNumber()).isNull();
    }

    @Test
    @DisplayName("23: 차량번호의 공백은 모두 제거해서 넘긴다")
    void vehicle_plateWhitespaceRemoved() throws Exception {
        when(customerService.updateVehicle(eq(1L), eq(4L), any(VehicleUpdateRequest.class)))
            .thenReturn(new VehicleUpdateResponse(4, "78라0004", "K5", 2020, 45_000, 1));
        patchVehicle("{\"version\":0,\"plateNumber\":\" 78 라 0004 \"}").andExpect(status().isOk());

        ArgumentCaptor<VehicleUpdateRequest> sent = ArgumentCaptor.forClass(VehicleUpdateRequest.class);
        verify(customerService).updateVehicle(eq(1L), eq(4L), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().plateNumber()).isEqualTo("78라0004");
    }

    @Test
    @DisplayName("23: version이 없거나 숫자가 아니면 400 INVALID_INPUT, Service를 부르지 않는다")
    void vehicle_versionRequired() throws Exception {
        patchVehicle("{\"model\":\"K5\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("version"));
        patchVehicle("{\"version\":\"abc\",\"model\":\"K5\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verifyNoInteractions(customerService);
    }

    @Test
    @DisplayName("23: 차종·차량번호를 비우거나 연식·주행거리 범위를 벗어나면 400 + 그 필드")
    void vehicle_invalidFields() throws Exception {
        patchVehicle("{\"version\":0,\"model\":\"  \"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("model"));
        patchVehicle("{\"version\":0,\"plateNumber\":\"   \"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("plateNumber"));
        patchVehicle("{\"version\":0,\"modelYear\":1979}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("modelYear"));
        patchVehicle("{\"version\":0,\"latestMileage\":-1}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("latestMileage"));
        patchVehicle("{\"version\":0,\"latestMileage\":2000001}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("latestMileage"));
        verifyNoInteractions(customerService);
    }

    @Test
    @DisplayName("23: Service의 409 CONCURRENT_MODIFICATION·404 NOT_FOUND를 그대로 응답")
    void vehicle_passesThroughServiceErrors() throws Exception {
        when(customerService.updateVehicle(eq(1L), eq(4L), any(VehicleUpdateRequest.class)))
            .thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        patchVehicle("{\"version\":0,\"model\":\"K5\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        when(customerService.updateVehicle(eq(1L), eq(4L), any(VehicleUpdateRequest.class)))
            .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        patchVehicle("{\"version\":0,\"model\":\"K5\"}").andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("23: 토큰 없으면 401, 서비스 관리자는 403")
    void vehicle_roles() throws Exception {
        String admin = "Bearer " + TestJwts.sign(SECRET, TestJwts.admin(1, Instant.now().plusSeconds(3_600)));
        mvc.perform(patch("/api/vehicles/4").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/vehicles/4").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":0}")).andExpect(status().isForbidden());
        verifyNoInteractions(customerService);

        when(customerService.updateVehicle(eq(1L), eq(4L), any(VehicleUpdateRequest.class)))
            .thenReturn(new VehicleUpdateResponse(4, "78라0004", "K5", 2020, 45_000, 0));
        mvc.perform(patch("/api/vehicles/4").header("Authorization", token(1, "MECHANIC"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isOk());
    }
}
