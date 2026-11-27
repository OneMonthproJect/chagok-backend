package com.wanted.chagok.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * {@code reservation_revision} 표 (07 4.2, V2): 예약 정정·재배정 이력. 한 번 남기면 고치지 않는 기록이라 setter가 없다.
 * 종류({@link RevisionKind})마다 쓰는 칼럼이 다르다: {@code STATUS}는 {@code prev_status}·{@code new_status}, {@code MEMO}는
 * {@code prev_memo}·{@code new_memo}, {@code ASSIGNEE}는 {@code prev_assignee_id}·{@code new_assignee_id}. 다른 종류의 칼럼은
 * NULL로 둔다(07 4.2: 서버가 지킨다) — 그래서 생성은 아래 팩토리 셋으로만 한다.
 * 다른 도메인(담당 정비사)은 ID로만 가진다. 이전·이후 값은 스냅샷이라 FK가 없다(07 4.2).
 */
@Entity
@Table(name = "reservation_revision")
@EntityListeners(AuditingEntityListener.class)
public class ReservationRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private Long shopId;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private Long reservationId;

    // validate가 MySQL enum 타입을 기대해 실패하는 것을 막으려 VARCHAR로 고정한다 (07 6.2)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private RevisionKind kind;

    @Column(name = "changed_by_staff_id", nullable = false, updatable = false)
    private Long changedByStaffId;

    @Column(name = "reason", length = 200, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "prev_status", length = 20, updatable = false)
    private ReservationStatus prevStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "new_status", length = 20, updatable = false)
    private ReservationStatus newStatus;

    @Column(name = "prev_memo", length = 200, updatable = false)
    private String prevMemo;

    @Column(name = "new_memo", length = 200, updatable = false)
    private String newMemo;

    @Column(name = "prev_assignee_id", updatable = false)
    private Long prevAssigneeId;

    @Column(name = "new_assignee_id", updatable = false)
    private Long newAssigneeId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ReservationRevision() {
    }

    private ReservationRevision(long shopId, long reservationId, RevisionKind kind, long changedByStaffId, String reason) {
        this.shopId = shopId;
        this.reservationId = reservationId;
        this.kind = kind;
        this.changedByStaffId = changedByStaffId;
        this.reason = reason;
    }

    /**
     * 담당 재배정 이력 (08 19번): 이전·이후 담당, 사유, 수정자. 시각은 저장할 때 JPA Auditing이 채운다.
     *
     * @param reason 재배정 사유. 당일 사장 재배정에서만 필수이고 그 밖에는 비어 있을 수 있다
     */
    public static ReservationRevision assigneeChanged(long shopId, long reservationId, long changedByStaffId,
                                                      String reason, long prevAssigneeId, long newAssigneeId) {
        ReservationRevision r = new ReservationRevision(shopId, reservationId, RevisionKind.ASSIGNEE, changedByStaffId, reason);
        r.prevAssigneeId = prevAssigneeId;
        r.newAssigneeId = newAssigneeId;
        return r;
    }

    /** 상태 정정 이력 (08 40번): 이전·이후 상태는 항상 있다 (V2 CHECK). */
    public static ReservationRevision statusCorrected(long shopId, long reservationId, long changedByStaffId, String reason,
                                                      ReservationStatus prevStatus, ReservationStatus newStatus) {
        ReservationRevision r = new ReservationRevision(shopId, reservationId, RevisionKind.STATUS, changedByStaffId, reason);
        r.prevStatus = prevStatus;
        r.newStatus = newStatus;
        return r;
    }

    /** 메모 정정 이력 (08 40번): 이전 메모는 없을 수 있고, 메모를 비운 정정이면 이후 메모가 없다. */
    public static ReservationRevision memoCorrected(long shopId, long reservationId, long changedByStaffId, String reason,
                                                    String prevMemo, String newMemo) {
        ReservationRevision r = new ReservationRevision(shopId, reservationId, RevisionKind.MEMO, changedByStaffId, reason);
        r.prevMemo = prevMemo;
        r.newMemo = newMemo;
        return r;
    }

    public Long getId() {
        return id;
    }

    public Long getShopId() {
        return shopId;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public RevisionKind getKind() {
        return kind;
    }

    public Long getChangedByStaffId() {
        return changedByStaffId;
    }

    public String getReason() {
        return reason;
    }

    public ReservationStatus getPrevStatus() {
        return prevStatus;
    }

    public ReservationStatus getNewStatus() {
        return newStatus;
    }

    public String getPrevMemo() {
        return prevMemo;
    }

    public String getNewMemo() {
        return newMemo;
    }

    public Long getPrevAssigneeId() {
        return prevAssigneeId;
    }

    public Long getNewAssigneeId() {
        return newAssigneeId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
