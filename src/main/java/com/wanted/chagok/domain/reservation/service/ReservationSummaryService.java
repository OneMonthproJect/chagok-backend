package com.wanted.chagok.domain.reservation.service;

import com.wanted.chagok.domain.reservation.dto.ReservationSummaryResponse;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository;
import com.wanted.chagok.domain.reservation.repository.ReservationRepository.ReservedStatusRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 42 월 요약 (08 7.1 42, 04 F-RSV-05). 날짜별 총 건수와 상태별 건수만 센다. 업무 규칙이 없는 조회·집계라
 * {@code ReservationService}와 파일이 겹치지 않게 따로 둔다. 정비소는 토큰의 값만 쓴다 (07 I-3).
 *
 * <p>상태는 이름 문자열로 센다 — {@code IN_PROGRESS}·{@code NO_SHOW}가 enum에 들어오기 전(#33·#36)에도 이 집계는 맞아야 해서다.
 */
@Service
@Transactional(readOnly = true)
public class ReservationSummaryService {

    private final ReservationRepository reservationRepository;

    public ReservationSummaryService(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    // LIMIT: 기간 최대 42일(08 15번과 같다)이라 행을 읽어 서비스에서 센다 / 정비소 예약이 크게 늘면 DB 집계 쿼리로 바꾼다
    public ReservationSummaryResponse summarize(long shopId, LocalDate from, LocalDate to) {
        ReservationService.validatePeriod(from, to);
        List<ReservedStatusRow> rows = reservationRepository.findStatusRowsInPeriod(
            shopId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        Map<LocalDate, Counts> byDate = new TreeMap<>();
        for (ReservedStatusRow row : rows) {
            byDate.computeIfAbsent(row.getReservedAt().toLocalDate(), day -> new Counts()).add(row.getStatus());
        }
        List<ReservationSummaryResponse.Item> items = byDate.entrySet().stream()
            .map(e -> e.getValue().toItem(e.getKey()))
            .toList();
        return new ReservationSummaryResponse(from, to, items);
    }

    /** 하루치 상태별 건수. 합계(total)는 취소만 빼고 더한다. */
    private static final class Counts {
        private int reserved;
        private int inProgress;
        private int completed;
        private int canceled;
        private int noShow;

        void add(String status) {
            switch (status) {
                case "RESERVED" -> reserved++;
                case "IN_PROGRESS" -> inProgress++;
                case "COMPLETED" -> completed++;
                case "CANCELED" -> canceled++;
                case "NO_SHOW" -> noShow++;
                // V1·V2의 ck_reservation_status CHECK가 이 다섯만 허용한다. 다른 값이면 서버 버그라 500으로 드러낸다
                default -> throw new IllegalStateException("알 수 없는 예약 상태: " + status);
            }
        }

        ReservationSummaryResponse.Item toItem(LocalDate date) {
            int total = reserved + inProgress + completed + noShow; // 취소만 제외 (D-63 B05)
            return new ReservationSummaryResponse.Item(date, total, reserved, inProgress, completed, canceled, noShow);
        }
    }
}
