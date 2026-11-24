package com.wanted.chagok.domain.account.entity;

import com.wanted.chagok.global.auth.StaffRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * staff 표 — 사장·정비사 계정 (07 3.3, V1). 로그인 확인에 필요한 칼럼만 매핑한다. 계정 발급·비밀번호 변경·비활성화(P1)에서
 * 칼럼과 이름 있는 메서드를 더한다. setter는 두지 않는다 (CONVENTIONS 5장). {@code shop}은 다른 도메인이라 ID로만 가진다.
 */
@Entity
@Table(name = "staff")
public class Staff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "login_id", nullable = false, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private StaffRole role;

    protected Staff() {
    }

    public Long getId() {
        return id;
    }

    public Long getShopId() {
        return shopId;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public StaffRole getRole() {
        return role;
    }
}
