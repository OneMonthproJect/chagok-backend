package com.wanted.chagok.domain.maintenance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * maintenance_record_revision 테이블 (07 4.1, V2): 정비 기록을 수정할 때마다 남기는 1행. 수정 <b>전</b> 값 전체({@code prev_*})만
 * 저장하고, 수정 후 값은 다음 수정의 수정 전 값(마지막은 지금 기록)에서 읽는다. 한 번 남기면 고치지 않아 setter가 없다.
 * 이전 값의 항목·담당은 스냅샷이라 FK가 없다(07 4.1). 수정 시각은 JPA Auditing이 채운다.
 */
@Entity
@Table(name = "maintenance_record_revision")
@EntityListeners(AuditingEntityListener.class)
public class MaintenanceRecordRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private long shopId;

    @Column(name = "record_id", nullable = false, updatable = false)
    private long recordId;

    @Column(name = "revision_no", nullable = false, updatable = false)
    private int revisionNo;

    @Column(name = "changed_by_staff_id", nullable = false, updatable = false)
    private long changedByStaffId;

    @Column(length = 200, updatable = false)
    private String reason;

    @Column(name = "prev_record_date", nullable = false, updatable = false)
    private LocalDate prevRecordDate;

    @Column(name = "prev_mileage", updatable = false)
    private Integer prevMileage;

    // validate가 MySQL enum 타입을 기대해 실패하는 것을 막으려 VARCHAR로 고정한다 (07 6.2)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "prev_record_type", nullable = false, updatable = false, length = 20)
    private RecordType prevRecordType;

    @Column(name = "prev_service_item_id", nullable = false, updatable = false)
    private long prevServiceItemId;

    @Column(name = "prev_inspect_result", length = 20, updatable = false)
    private String prevInspectResult;

    @Column(name = "prev_memo", length = 200, updatable = false)
    private String prevMemo;

    @Column(name = "prev_staff_id", nullable = false, updatable = false)
    private long prevStaffId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected MaintenanceRecordRevision() {
    }

    /** 수정 직전의 기록 값을 그대로 옮겨 남긴다. {@code revisionNo}는 그 기록의 몇 번째 수정인지(1부터). */
    public static MaintenanceRecordRevision before(MaintenanceRecord record, int revisionNo, long changedByStaffId,
                                                   String reason) {
        MaintenanceRecordRevision r = new MaintenanceRecordRevision();
        r.shopId = record.getShopId();
        r.recordId = record.getId();
        r.revisionNo = revisionNo;
        r.changedByStaffId = changedByStaffId;
        r.reason = reason;
        r.prevRecordDate = record.getRecordDate();
        r.prevMileage = record.getMileage();
        r.prevRecordType = record.getRecordType();
        r.prevServiceItemId = record.getServiceItemId();
        r.prevInspectResult = record.getInspectResult();
        r.prevMemo = record.getMemo();
        r.prevStaffId = record.getStaffId();
        return r;
    }

    public Long getId() {
        return id;
    }

    public long getShopId() {
        return shopId;
    }

    public long getRecordId() {
        return recordId;
    }

    public int getRevisionNo() {
        return revisionNo;
    }

    public long getChangedByStaffId() {
        return changedByStaffId;
    }

    public String getReason() {
        return reason;
    }

    public LocalDate getPrevRecordDate() {
        return prevRecordDate;
    }

    public Integer getPrevMileage() {
        return prevMileage;
    }

    public RecordType getPrevRecordType() {
        return prevRecordType;
    }

    public long getPrevServiceItemId() {
        return prevServiceItemId;
    }

    public String getPrevInspectResult() {
        return prevInspectResult;
    }

    public String getPrevMemo() {
        return prevMemo;
    }

    public long getPrevStaffId() {
        return prevStaffId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
