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
 * maintenance_record 테이블 (07 3.7, V1). 항목마다 1행이고, 한 번 저장한 방문의 행은 모두 같은 {@code visitKey}를 가진다.
 * 정비소 데이터이므로 조회는 항상 {@code shopId}와 함께. 차량·항목·담당 정비사는 복합 FK를 JPA 연관으로 풀지 않고 id로만 가리킨다.
 * V1에는 {@code updated_at}·{@code version}이 없다(P0-나머지 V2). 저장(API 12)은 #42가 팩토리 메서드를 더한다.
 */
@Entity
@Table(name = "maintenance_record")
@EntityListeners(AuditingEntityListener.class)
public class MaintenanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private long shopId;

    @Column(name = "vehicle_id", nullable = false, updatable = false)
    private long vehicleId;

    @Column(name = "service_item_id", nullable = false, updatable = false)
    private long serviceItemId;

    /** CHAR(36) UUID */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "visit_key", nullable = false, updatable = false, length = 36)
    private String visitKey;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column
    private Integer mileage;

    // validate가 MySQL enum 타입을 기대해 실패하는 것을 막으려 VARCHAR로 고정한다 (07 6.2)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "record_type", nullable = false, length = 20)
    private RecordType recordType;

    /** SHOP · CUSTOMER_STATEMENT. 외부(EXTERNAL)면 CUSTOMER_STATEMENT, 나머지는 SHOP (DB CHECK). */
    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "staff_id", nullable = false)
    private long staffId;

    @Column(length = 200)
    private String memo;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected MaintenanceRecord() {
    }

    public Long getId() {
        return id;
    }

    public long getShopId() {
        return shopId;
    }

    public long getVehicleId() {
        return vehicleId;
    }

    public long getServiceItemId() {
        return serviceItemId;
    }

    public String getVisitKey() {
        return visitKey;
    }

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public Integer getMileage() {
        return mileage;
    }

    public RecordType getRecordType() {
        return recordType;
    }

    public String getSource() {
        return source;
    }

    public long getStaffId() {
        return staffId;
    }

    public String getMemo() {
        return memo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
