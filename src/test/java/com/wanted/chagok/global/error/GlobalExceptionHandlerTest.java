package com.wanted.chagok.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wanted.chagok.global.response.ApiResponse;
import com.wanted.chagok.global.web.PageLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 전역 처리기 → 08 0.2 모양·0.3 코드. 보안 필터 없이 MVC만 띄운다(DB 없음). 테스트 전용 Controller로 예외를 만든다.
 * 보안 필터의 401·403은 EndpointGuardTest.
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    @DisplayName("업무 예외 → 그 코드·상태·details (409 RESERVATION_ACTIVE_EXISTS + details.reservationId)")
    void businessException() throws Exception {
        mvc.perform(get("/probe/business"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code").value("RESERVATION_ACTIVE_EXISTS"))
            .andExpect(jsonPath("$.error.fieldErrors").isArray())
            .andExpect(jsonPath("$.error.fieldErrors").isEmpty())
            .andExpect(jsonPath("$.error.details.reservationId").value(3));
    }

    @Test
    @DisplayName("@Valid 실패 → 400 INVALID_INPUT + fieldErrors[{field, reason}]")
    void validationFailure() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"))
            .andExpect(jsonPath("$.error.fieldErrors[0].reason").isNotEmpty())
            .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    @DisplayName("잘못된 JSON·405·415 → 400 INVALID_INPUT, fieldErrors 빈 배열 (08 0.3: 표준 예외도 감싼다)")
    void standardBadRequests() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{broken"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors").isEmpty());
        mvc.perform(delete("/probe/body"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("x"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("필수 파라미터 누락·숫자가 아닌 값 → 400 + 그 파라미터 이름")
    void parameterErrors() throws Exception {
        mvc.perform(get("/probe/required"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("q"));
        mvc.perform(get("/probe/page").param("page", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("page"));
    }

    @Test
    @DisplayName("PageLimit: 생략 0·20, 50 초과 → 50, page 음수·size 0 → 400 (08 0.1)")
    void pageLimitThroughController() throws Exception {
        mvc.perform(get("/probe/page"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20));
        mvc.perform(get("/probe/page").param("size", "51"))
            .andExpect(jsonPath("$.data.size").value(50));
        mvc.perform(get("/probe/page").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("page"));
        mvc.perform(get("/probe/page").param("size", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("size"));
    }

    @Test
    @DisplayName("@Version 충돌 → 409 CONCURRENT_MODIFICATION")
    void optimisticLock() throws Exception {
        mvc.perform(get("/probe/lock"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("예상 못 한 예외·DB 제약 위반 → 500 INTERNAL_ERROR, 응답에 예외 메시지·SQL 없음 (08 0.3 M1 결정 A)")
    void unexpectedErrorsHideInternals() throws Exception {
        String body = mvc.perform(get("/probe/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("select").doesNotContain("password").doesNotContain("IllegalStateException");

        String dbBody = mvc.perform(get("/probe/constraint"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andReturn().getResponse().getContentAsString();
        assertThat(dbBody).doesNotContain("fk_vehicle_customer").doesNotContain("CONFLICT");
    }

    @Test
    @DisplayName("Accept가 JSON이 아니어도(406 상황) 오류는 JSON 공통 응답으로 나가고 원래 상태 코드를 지킨다 (#17)")
    void errorBodyIgnoresAcceptHeader() throws Exception {
        // 처리기가 응답을 쓰지 못해 /error로 넘어가면 보안 필터가 403으로 바꾼다 — Content-Type을 고정해 협상에서 빠진다
        mvc.perform(get("/probe/business").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error.code").value("RESERVATION_ACTIVE_EXISTS"));
        mvc.perform(get("/probe/boom").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("Controller가 던진 406(Accept 협상 실패) → 400 INVALID_INPUT, 405·415와 같은 규칙 (#17)")
    void notAcceptable() throws Exception {
        mvc.perform(get("/probe/not-acceptable").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.error.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("AccessDeniedException → 403 FORBIDDEN (500으로 바뀌지 않는다), 메시지는 응답에 없다 (#17)")
    void accessDenied() throws Exception {
        String body = mvc.perform(get("/probe/denied"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret-reason");
    }

    @Test
    @DisplayName("ResponseStatusException → 그 상태 (400·403·404는 해당 코드, 그 밖 4xx는 INVALID_INPUT, 5xx는 INTERNAL_ERROR), reason은 응답에 없다 (#17)")
    void responseStatusException() throws Exception {
        mvc.perform(get("/probe/status/404"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/probe/status/403"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/probe/status/401"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/probe/status/400"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(get("/probe/status/422"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        String body = mvc.perform(get("/probe/status/503"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("reason-text");
    }

    @Test
    @DisplayName("forHttpStatus 표: 401·403·404는 그 코드, 그 밖 4xx(405·431 포함)와 3xx·2xx는 INVALID_INPUT, 5xx는 INTERNAL_ERROR (/error와 ResponseStatusException이 같은 규칙)")
    void forHttpStatusTable() {
        assertThat(GlobalExceptionHandler.forHttpStatus(401)).isEqualTo(ErrorCode.AUTH_REQUIRED);
        assertThat(GlobalExceptionHandler.forHttpStatus(403)).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(GlobalExceptionHandler.forHttpStatus(404)).isEqualTo(ErrorCode.NOT_FOUND);
        for (int status : new int[] {200, 302, 405, 431}) {
            assertThat(GlobalExceptionHandler.forHttpStatus(status)).as("status %d", status).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        for (int status : new int[] {500, 503}) {
            assertThat(GlobalExceptionHandler.forHttpStatus(status)).as("status %d", status).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Test
    @DisplayName("없는 경로 → 404 NOT_FOUND")
    void unknownPath() throws Exception {
        mvc.perform(get("/probe/nothing-here"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    record Body(@NotBlank String name) {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/business")
        ApiResponse<Void> business() {
            throw new BusinessException(ErrorCode.RESERVATION_ACTIVE_EXISTS, Map.of("reservationId", 3));
        }

        @PostMapping("/probe/body")
        ApiResponse<String> body(@Valid @RequestBody Body body) {
            return ApiResponse.ok(body.name());
        }

        @GetMapping("/probe/required")
        ApiResponse<String> required(@RequestParam String q) {
            return ApiResponse.ok(q);
        }

        @GetMapping("/probe/page")
        ApiResponse<PageLimit> page(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
            return ApiResponse.ok(PageLimit.of(page, size));
        }

        @GetMapping("/probe/lock")
        ApiResponse<Void> lock() {
            throw new OptimisticLockingFailureException("row was updated by another transaction");
        }

        @GetMapping("/probe/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException("select * from staff where password = 'x'");
        }

        @GetMapping("/probe/not-acceptable")
        ApiResponse<Void> notAcceptable() throws HttpMediaTypeNotAcceptableException {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        }

        @GetMapping("/probe/denied")
        ApiResponse<Void> denied() {
            throw new AccessDeniedException("secret-reason");
        }

        @GetMapping("/probe/status/{code}")
        ApiResponse<Void> status(@PathVariable int code) {
            throw new ResponseStatusException(HttpStatus.valueOf(code), "reason-text");
        }

        @GetMapping("/probe/constraint")
        ApiResponse<Void> constraint() {
            throw new DataIntegrityViolationException("Cannot add or update a child row: fk_vehicle_customer");
        }
    }
}
