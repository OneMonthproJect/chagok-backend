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
 * 지금은 담당 재배정({@link RevisionKind#ASSIGNEE})에 쓰는 칼럼만 매핑한다. 상태·메모 칼럼({@code prev_status} 등)은 정정(API 40,
 * backend#38)이 더한다 — 매핑하지 않은 칼럼은 NULL 허용이라 {@code ddl-auto: validate}에 걸리지 않는다.
 * 다른 도메인(담당 정비사)은 ID로만 가진다. 이전·이후 담당은 스냅샷 값이라 FK가 없다(07 4.2).
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

    @Column(name = "prev_assignee_id", updatable = false)
    private Long prevAssigneeId;

    @Column(name = "new_assignee_id", updatable = false)
    private Long newAssigneeId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ReservationRevision() {
    }

    private ReservationRevision(long shopId, long reservationId, long changedByStaffId, String reason,
                                long prevAssigneeId, long newAssigneeId) {
        this.shopId = shopId;
        this.reservationId = reservationId;
        this.kind = RevisionKind.ASSIGNEE;
        this.changedByStaffId = changedByStaffId;
        this.reason = reason;
        this.prevAssigneeId = prevAssigneeId;
        this.newAssigneeId = newAssigneeId;
    }

    /**
     * 담당 재배정 이력 (08 19번): 이전·이후 담당, 사유, 수정자. 시각은 저장할 때 JPA Auditing이 채운다.
     *
     * @param reason 재배정 사유. 당일 사장 재배정에서만 필수이고 그 밖에는 비어 있을 수 있다
     */
    public static ReservationRevision assigneeChanged(long shopId, long reservationId, long changedByStaffId,
                                                      String reason, long prevAssigneeId, long newAssigneeId) {
        return new ReservationRevision(shopId, reservationId, changedByStaffId, reason, prevAssigneeId, newAssigneeId);
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
