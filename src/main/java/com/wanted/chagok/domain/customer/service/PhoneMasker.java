package com.wanted.chagok.domain.customer.service;

/**
 * 목록 응답(04·05·14·15)의 연락처 마스킹 규칙 한 곳 (08 0.5, D-63 B07, F-CUS-07). 상세 응답(07·09·17)과 문자 초안(28)은
 * 전체 번호라 쓰지 않는다. 다른 도메인은 이 클래스가 아니라 {@code CustomerReader.maskPhone}으로 부른다(CONVENTIONS 2장: api에는 구현을 두지 않는다). 화면이 다시 가리거나 풀지 않으므로 서버가 목록을 만들 때 이 규칙을 한 번만 적용한다.
 *
 * <p>저장된 값에서 숫자만 센다(하이픈 무시). 숫자가 n자리일 때:
 * <ul>
 *   <li>9 이상: 앞 머리({@code 02}로 시작하면 2자리, 아니면 3자리) + {@code -} + 가운데 숫자 수만큼 {@code *} + {@code -} + 뒤 4자리</li>
 *   <li>5~8: 앞 숫자 수만큼 {@code *} + {@code -} + 뒤 4자리</li>
 *   <li>4 이하: 숫자 수만큼 {@code *}</li>
 * </ul>
 * 원래 하이픈 위치는 따르지 않는다(같은 번호면 입력 형식이 달라도 같은 표시). 비어 있는 값(익명화 고객 등)은 그대로 돌려준다.
 * 숫자가 하나도 없는 비어 있지 않은 값은 번호가 아니므로 {@code ""}로 돌려 내용이 새지 않게 한다(08에는 없는 안전한 선택).
 */
public final class PhoneMasker {

    private static final int TAIL = 4;

    private PhoneMasker() {
    }

    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        int n = digits.length();
        if (n == 0) {
            return phone.isBlank() ? phone : "";
        }
        if (n <= TAIL) {
            return "*".repeat(n);
        }
        String tail = digits.substring(n - TAIL);
        if (n <= 8) {
            return "*".repeat(n - TAIL) + "-" + tail;
        }
        int head = digits.startsWith("02") ? 2 : 3;
        return digits.substring(0, head) + "-" + "*".repeat(n - head - TAIL) + "-" + tail;
    }
}
