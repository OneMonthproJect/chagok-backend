package com.wanted.chagok.domain.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
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

    /** 동시 수정 확인용 번호 (07 4장, POL-124). 0에서 시작하고, 값이 바뀌어 저장될 때마다 JPA가 1 올린다. */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

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

    /** 요청 {@code version}이 DB 값과 같은지 서버가 직접 비교한다 (08 22번). 다르면 409 {@code CONCURRENT_MODIFICATION}. */
    public void requireVersion(int expectedVersion) {
        if (version == null || version != expectedVersion) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    /**
     * 보낸 필드만 바꾼다 (04 F-CUS-06). {@code null}은 "보내지 않음"이다. 값이 지금과 같으면 아무것도 바뀌지 않아
     * 저장해도 UPDATE가 없고 {@code version}도 오르지 않는다. 값 검증은 요청 DTO가 한다.
     */
    public void update(String name, String phone, Boolean consent) {
        if (name != null) {
            this.name = name;
        }
        if (phone != null) {
            this.phone = phone;
        }
        if (consent != null) {
            this.consent = consent;
        }
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

    public Integer getVersion() {
        return version;
    }
}
