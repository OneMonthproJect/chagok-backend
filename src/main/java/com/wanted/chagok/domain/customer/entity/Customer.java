package com.wanted.chagok.domain.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** customer 테이블 (07 3.4, V1). 정비소 데이터이므로 조회는 항상 {@code shopId}와 함께. */
@Entity
@Table(name = "customer")
@EntityListeners(AuditingEntityListener.class)
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private long shopId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false)
    private boolean consent;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Customer() {
    }

    private Customer(long shopId, String name, String phone, boolean consent) {
        this.shopId = shopId;
        this.name = name;
        this.phone = phone;
        this.consent = consent;
    }

    /** 신규 등록. 값 검증(길이·형식)은 요청 DTO가 하고, 여기서는 저장할 값만 받는다. */
    public static Customer register(long shopId, String name, String phone, boolean consent) {
        return new Customer(shopId, name, phone, consent);
    }

    public Long getId() {
        return id;
    }

    public long getShopId() {
        return shopId;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isConsent() {
        return consent;
    }
}
