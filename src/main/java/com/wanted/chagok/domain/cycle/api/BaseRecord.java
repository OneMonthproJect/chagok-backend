package com.wanted.chagok.domain.cycle.api;

import java.time.LocalDate;

/**
 * 계산에 쓴 기준 기록 (08 10번 {@code baseRecord}: {@code { date, mileage, type }}).
 *
 * @param date    기준 기록일
 * @param mileage 기준 기록의 주행거리. 없으면 null
 * @param type    기준 기록의 구분 표시값 ({@link CycleRecord#type()} 그대로)
 */
public record BaseRecord(LocalDate date, Integer mileage, String type) {
}
