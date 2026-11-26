package com.wanted.chagok.domain.reservation.entity;

import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * {@code reservation} 표 (07 3.8, V1). 칼럼은 V1과 같고 {@code ddl-auto: validate}로 확인한다.
 *
 * <p>다른 도메인(차량·담당 정비사)은 ID로만 가진다(CONVENTIONS 5장). 복합 FK는 Flyway에만 있다(07 6.2).
 * setter는 없고 상태 변경은 이름 있는 메서드로만 한다(CONVENTIONS 4장 — 끝난 예약을 바꾸려 하면 여기서 거절한다).
 * 동시 수정은 {@code @Version}이 막는다(D-42).
 */
@Entity
@Table(name = "reservation")
@EntityListeners(AuditingEntityListener.class)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private Long shopId;

    @Column(name = "vehicle_id", nullable = false, updatable = false)
    private Long vehicleId;

    @Column(name = "reserved_at", nullable = false)
    private LocalDateTime reservedAt;

    // validate가 MySQL enum 타입을 기대해 실패하는 것을 막으려 VARCHAR로 고정한다 (07 6.2)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "purpose_other", nullable = false)
    private boolean purposeOther;

    @Column(name = "purpose_memo", length = 200)
    private String purposeMemo;

    @Column(name = "assignee_staff_id", nullable = false)
    private Long assigneeStaffId;

    @Column(name = "created_by_staff_id", nullable = false, updatable = false)
    private Long createdByStaffId;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Reservation() {
    }

    private Reservation(long shopId, long vehicleId, LocalDateTime reservedAt, boolean purposeOther, String purposeMemo,
                        long staffId) {
        this.shopId = shopId;
        this.vehicleId = vehicleId;
        this.reservedAt = reservedAt;
        this.status = ReservationStatus.RESERVED;
        this.purposeOther = purposeOther;
        this.purposeMemo = purposeMemo;
        // 근거: 04 F-RSV-01, D-51 — P0-핵심은 요청에 담당이 없고 등록한 계정이 담당이다
        this.assigneeStaffId = staffId;
        this.createdByStaffId = staffId;
    }

    /**
     * 새 예약. 상태는 {@code RESERVED}, {@code version}은 저장할 때 0에서 시작한다(08 16번).
     * 30분 단위·날짜·같은 차량 유효 예약 같은 입력 검증은 service가 먼저 한다.
     */
    public static Reservation register(long shopId, long vehicleId, LocalDateTime reservedAt, boolean purposeOther,
                                       String purposeMemo, long staffId) {
        return new Reservation(shopId, vehicleId, reservedAt, purposeOther, purposeMemo, staffId);
    }

    /**
     * 상태·일시를 바꿀 수 있는 예약인지 확인한다 (08 18·19번 공통 순서). 지금 상태가 {@code RESERVED}가 아니면
     * 409 {@code INVALID_STATUS_TRANSITION}, 요청 {@code version}이 DB 값과 다르면 409 {@code CONCURRENT_MODIFICATION}.
     * 상태 확인이 먼저라서 다른 직원이 이미 끝낸 예약에 낡은 {@code version}으로 보내면 상태 오류가 난다(R10).
     * 비교한 뒤 저장 사이의 충돌은 {@code @Version}이 잡는다.
     *
     * @param expectedVersion 화면이 읽은 예약의 {@code version}
     */
    public void assertChangeable(int expectedVersion) {
        if (status != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        requireVersion(expectedVersion);
    }

    /** 요청 {@code version}이 DB 값과 같은지 서버가 직접 비교한다 (08 18~21번). 다르면 409 {@code CONCURRENT_MODIFICATION}. */
    private void requireVersion(int expectedVersion) {
        if (version == null || version != expectedVersion) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    /**
     * 방문 완료·취소처럼 {@code RESERVED}뿐 아니라 {@code IN_PROGRESS}에서도 되는 전이의 확인 (08 18번, D-52).
     * 순서는 {@link #assertChangeable}와 같다: 상태(409 {@code INVALID_STATUS_TRANSITION}) → {@code version}(409
     * {@code CONCURRENT_MODIFICATION}).
     */
    public void assertCompletable(int expectedVersion) {
        if (status != ReservationStatus.RESERVED && status != ReservationStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        requireVersion(expectedVersion);
    }

    /** 방문 완료 (08 18번, 04 F-RSV-04). 시각 전·후 모두, 예약 또는 진행중에서 가능하다. 확인 순서는 {@link #assertCompletable}. */
    public void complete(int expectedVersion) {
        assertCompletable(expectedVersion);
        this.status = ReservationStatus.COMPLETED;
    }

    /**
     * 방문 접수 (08 39번, 04 F-RSV-09, D-52): 차량이 도착해 예약이 진행중이 된다. {@code RESERVED}에서만, 시각 전·후 모두 가능하다
     * (D-23). 확인 순서는 {@link #assertChangeable}. 진행중 예약의 변경(19)은 같은 확인에서 409로 막힌다.
     */
    public void start(int expectedVersion) {
        assertChangeable(expectedVersion);
        this.status = ReservationStatus.IN_PROGRESS;
    }

    /**
     * 취소 (08 20번, 04 F-RSV-07). {@code RESERVED}·{@code IN_PROGRESS}에서 시각 전·후 모두 가능하고, 끝난 예약(완료·취소·노쇼)은
     * 409 {@code INVALID_STATUS_TRANSITION}. 순서는 18과 같다: 상태 → {@code version}(409 {@code CONCURRENT_MODIFICATION}).
     * 취소 사유는 받지 않는다 (POL-100).
     */
    public void cancel(int expectedVersion) {
        assertCompletable(expectedVersion);
        this.status = ReservationStatus.CANCELED;
    }

    /**
     * 노쇼 (08 21번, 04 F-RSV-08, R6). {@code RESERVED}이고 예약 시각이 현재보다 이전인 예약만 가능하다. 순서: 상태 →
     * {@code version} → 시각(아니면 409 {@code INVALID_STATUS_TRANSITION}). 시각 판정은 "처리 필요"({@link #needsAction})와 같은
     * 기준(예약 시각이 현재보다 이전)이다.
     *
     * @param now 호출하는 쪽이 {@code Clock}에서 꺼내 넘긴다(Entity는 시각을 직접 읽지 않는다)
     */
    public void markNoShow(int expectedVersion, LocalDateTime now) {
        if (status != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        requireVersion(expectedVersion);
        if (!reservedAt.isBefore(now)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = ReservationStatus.NO_SHOW;
    }

    /**
     * 예약 변경 (08 19번, D-42): 값이 있는 것만 바꾼다. 고객·차량은 바꿀 수 없어 인자에 없다. 입력 검증(30분 단위·날짜·항목)과
     * {@link #assertChangeable}는 호출하는 쪽이 먼저 했다는 전제다(상태 규칙은 그 한 곳에만 둔다).
     *
     * @param now          호출하는 쪽이 {@code Clock}에서 꺼내 넘긴다(Entity는 시각을 직접 읽지 않는다). 수정 시각에 쓴다
     * @param reservedAt   null이면 그대로
     * @param purposeOther null이면 그대로
     * @param purposeMemo  null이면 그대로(비우려면 빈 문자열)
     */
    public void change(LocalDateTime now, LocalDateTime reservedAt, Boolean purposeOther, String purposeMemo) {
        // 예약 목적 항목만 바뀌면 이 행의 칼럼은 그대로라 @Version이 오르지 않는다(R11). 그래서 수정 시각을 항상 앞으로 밀어
        // 행을 확실히 "바뀐 것"으로 만든다 — 08 19번이 허용한 updated_at 갱신 방식. 같은 초에 겹쳐도 한 칸 앞으로 둔다.
        this.updatedAt = updatedAt != null && !now.isAfter(updatedAt) ? updatedAt.plusSeconds(1) : now;
        if (reservedAt != null) {
            this.reservedAt = reservedAt;
        }
        if (purposeOther != null) {
            this.purposeOther = purposeOther;
        }
        if (purposeMemo != null) {
            this.purposeMemo = purposeMemo.isEmpty() ? null : purposeMemo;
        }
    }

    /**
     * "처리 필요" 표시 (08 15번 {@code needsAction}, D-23): 상태가 {@code RESERVED}인데 예약 시각이 현재보다 이전.
     * 시각이 지나도 상태는 바뀌지 않고 화면이 이 값으로 표시한다. 판정은 여기 한 곳이다(CONVENTIONS 4장).
     *
     * @param now 호출하는 쪽이 {@code Clock}에서 꺼내 넘긴다(Entity는 시각을 직접 읽지 않는다)
     */
    public boolean needsAction(LocalDateTime now) {
        return status == ReservationStatus.RESERVED && reservedAt.isBefore(now);
    }

    public Long getId() {
        return id;
    }

    public Long getShopId() {
        return shopId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public LocalDateTime getReservedAt() {
        return reservedAt;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public boolean isPurposeOther() {
        return purposeOther;
    }

    public String getPurposeMemo() {
        return purposeMemo;
    }

    public Long getAssigneeStaffId() {
        return assigneeStaffId;
    }

    public Long getCreatedByStaffId() {
        return createdByStaffId;
    }

    public Integer getVersion() {
        return version;
    }
}
