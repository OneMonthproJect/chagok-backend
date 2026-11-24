package com.wanted.chagok.domain.reservation.entity;

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
 * {@code reservation_item} 표 — 예약 목적으로 고른 정비 항목 (07 3.9, D-38). {@code updated_at}이 없는 표라
 * {@code created_at}만 매핑한다(07 6.2). 예약·항목은 같은 도메인 연관이 아니라 ID로만 가진다.
 */
@Entity
@Table(name = "reservation_item")
@EntityListeners(AuditingEntityListener.class)
public class ReservationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private Long shopId;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private Long reservationId;

    @Column(name = "service_item_id", nullable = false, updatable = false)
    private Long serviceItemId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ReservationItem() {
    }

    private ReservationItem(long shopId, long reservationId, long serviceItemId) {
        this.shopId = shopId;
        this.reservationId = reservationId;
        this.serviceItemId = serviceItemId;
    }

    public static ReservationItem of(long shopId, long reservationId, long serviceItemId) {
        return new ReservationItem(shopId, reservationId, serviceItemId);
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

    public Long getServiceItemId() {
        return serviceItemId;
    }
}
