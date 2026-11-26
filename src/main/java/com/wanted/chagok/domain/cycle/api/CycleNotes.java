package com.wanted.chagok.domain.cycle.api;

/**
 * 항목 하나의 표시 판정 결과 (04 F-CYC-02). 두 표시 모두 상태(미확인·여유·임박·도래)를 바꾸지 않는다.
 * 둘 다 "그 항목의 가장 최근 기록" 하나에서 나오므로 동시에 값이 있을 수 없다.
 *
 * @param lastDecline 지난 방문 거절. 없으면 null
 * @param lastCaution 지난 점검 주의. 없으면 null
 */
public record CycleNotes(CycleNote lastDecline, CycleNote lastCaution) {

    /** 표시 없음. */
    public static final CycleNotes NONE = new CycleNotes(null, null);

    public CycleNotes {
        if (lastDecline != null && lastCaution != null) {
            throw new IllegalArgumentException("거절과 점검 주의는 가장 최근 기록 하나에서만 나온다 (04 F-CYC-02)");
        }
    }
}
