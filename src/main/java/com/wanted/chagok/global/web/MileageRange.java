package com.wanted.chagok.global.web;

/**
 * 주행거리 입력 범위의 한 곳 (08 0.1, D-63 B03): 정수 0~2,000,000 km. 차량의 최근 주행거리(06·08·23·39),
 * 방문·외부 정비 주행거리(12·37), 항목 상태 미리보기 쿼리(10)가 모두 이 범위를 쓴다.
 *
 * <p>숫자와 범위 판정만 여기 둔다. 위반 때의 오류 문구는 API마다 이미 정해져 화면이 그대로 보여 주므로 각 검증 위치에 둔다.
 * {@code @Min}·{@code @Max}에 쓸 수 있게 상수는 컴파일 시점 상수다. 쪽 크기 상한({@link PageLimit})과 같은 08 0.1 입력 규칙이라
 * {@code global/web}에 둔다.
 */
public final class MileageRange {

    public static final int MIN = 0;
    public static final int MAX = 2_000_000;

    private MileageRange() {
    }

    /** 값이 있고 범위 밖이면 true. {@code null}은 입력하지 않은 것이라 범위 밖이 아니다(필수 여부는 호출하는 쪽이 본다). */
    public static boolean isOutOfRange(Integer km) {
        return km != null && (km < MIN || km > MAX);
    }
}
