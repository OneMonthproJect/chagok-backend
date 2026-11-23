package com.wanted.chagok.global.web;

import com.wanted.chagok.global.error.BusinessException;

/**
 * 목록 쪽 나누기의 상한을 자르는 한 곳 (08 0.1, CONVENTIONS 6장).
 *
 * <p>생략하면 page 0·size 20. size가 50을 넘으면 50으로 줄인다(응답 {@code size}도 50). page 음수·size 0 이하는
 * 400 {@code INVALID_INPUT}. 숫자가 아닌 값은 Controller의 {@code @RequestParam Integer} 변환에서 400이 된다
 * ({@code GlobalExceptionHandler}). 쪽 나누기를 쓰는 API는 05·11과 (P1) 48뿐이다.
 *
 * <pre>{@code
 * @GetMapping("/api/customers")
 * ApiResponse<…> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
 *     PageLimit limit = PageLimit.of(page, size);
 *     … repository.find…(shopId, PageRequest.of(limit.page(), limit.size(), sort)) …
 * }
 * }</pre>
 *
 * <p>Spring Data의 {@code Pageable}로 바꾸는 일은 도메인에서 한다. global이 {@code org.springframework.data.domain}을
 * 쓰면 ArchitectureTest "global은 domain을 모른다"의 패키지 패턴({@code ..domain..})에 걸린다.
 */
public record PageLimit(int page, int size) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    public PageLimit {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("PageLimit.of()로 만든다");
        }
    }

    public static PageLimit of(Integer page, Integer size) {
        int p = page == null ? DEFAULT_PAGE : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw BusinessException.invalidField("page", "0 이상이어야 합니다.");
        }
        if (s < 1) {
            throw BusinessException.invalidField("size", "1 이상이어야 합니다.");
        }
        return new PageLimit(p, Math.min(s, MAX_SIZE));
    }
}
