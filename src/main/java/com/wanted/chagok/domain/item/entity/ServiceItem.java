package com.wanted.chagok.domain.item.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * service_item 테이블 (07 3.6, V1). 정비소 데이터이므로 조회는 항상 {@code shopId}와 함께.
 * {@code code}는 enum이 아니라 VARCHAR(30), {@code updated_at} 컬럼은 없다.
 */
@Entity
@Table(name = "service_item")
@EntityListeners(AuditingEntityListener.class)
public class ServiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private long shopId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 30)
    private String name;

    /** SMALLINT */
    @Column(name = "cycle_months", nullable = false)
    private Short cycleMonths;

    @Column(name = "cycle_km", nullable = false)
    private Integer cycleKm;

    @Column(nullable = false)
    private boolean active;

    /** SMALLINT */
    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ServiceItem() {
    }

    public Long getId() {
        return id;
    }

    public long getShopId() {
        return shopId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Short getCycleMonths() {
        return cycleMonths;
    }

    public Integer getCycleKm() {
        return cycleKm;
    }

    public boolean isActive() {
        return active;
    }

    public Short getSortOrder() {
        return sortOrder;
    }
}
