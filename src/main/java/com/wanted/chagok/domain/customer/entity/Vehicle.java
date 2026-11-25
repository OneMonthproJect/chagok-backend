package com.wanted.chagok.domain.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * vehicle 테이블 (07 3.5, V1). 같은 정비소의 고객에만 붙는다(DB 복합 FK). 차량번호 중복은 막지 않는다.
 * 고객은 {@code customerId}로만 가리킨다(복합 FK (shop_id, customer_id)를 JPA 연관으로 풀지 않는다).
 */
@Entity
@Table(name = "vehicle")
@EntityListeners(AuditingEntityListener.class)
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private long shopId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;

    @Column(name = "plate_number", nullable = false, length = 20)
    private String plateNumber;

    @Column(length = 50)
    private String model;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "model_year")
    private Integer modelYear;

    /** 07 3.5 {@code latest_mileage}. 06 요청에서는 {@code mileage}라고 부른다(08). */
    @Column(name = "latest_mileage")
    private Integer latestMileage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Vehicle() {
    }

    private Vehicle(long shopId, long customerId, String plateNumber, String model, Integer modelYear,
        Integer latestMileage) {
        this.shopId = shopId;
        this.customerId = customerId;
        this.plateNumber = plateNumber;
        this.model = model;
        this.modelYear = modelYear;
        this.latestMileage = latestMileage;
    }

    public static Vehicle register(long shopId, long customerId, String plateNumber, String model, Integer modelYear,
        Integer latestMileage) {
        return new Vehicle(shopId, customerId, plateNumber, model, modelYear, latestMileage);
    }

    /**
     * 방문·정비 기록 저장 때 차량 값을 채운다 (04 F-MNT-01, 05 POL-60).
     * 연식은 비어 있을 때만 채우고(이미 있으면 그대로), 최근 주행거리는 비어 있거나 더 클 때만 갱신한다(작으면 그대로).
     * 값의 범위 검증은 호출하는 서비스가 한다.
     */
    public void applyVisit(Integer modelYear, Integer mileage) {
        if (this.modelYear == null && modelYear != null) {
            this.modelYear = modelYear;
        }
        if (mileage != null && (this.latestMileage == null || mileage > this.latestMileage)) {
            this.latestMileage = mileage;
        }
    }

    public Long getId() {
        return id;
    }

    public long getShopId() {
        return shopId;
    }

    public long getCustomerId() {
        return customerId;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public String getModel() {
        return model;
    }

    public Integer getModelYear() {
        return modelYear;
    }

    public Integer getLatestMileage() {
        return latestMileage;
    }
}
