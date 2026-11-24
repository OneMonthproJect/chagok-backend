package com.wanted.chagok.domain.shop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * shop 표 (07 3.2, V1). 지금은 읽기에 필요한 칼럼만 매핑한다. 개설·정지(P1)에서 칼럼과 상태 전이 메서드를 더한다.
 * setter는 두지 않는다 (CONVENTIONS 5장).
 */
@Entity
@Table(name = "shop")
public class Shop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    protected Shop() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
