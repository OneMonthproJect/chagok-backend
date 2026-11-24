package com.wanted.chagok.domain.shop.api;

/**
 * 다른 도메인이 보는 정비소 값 (Entity 아님). 지금은 08 01·03의 {@code shopName}에 필요한 것만 둔다.
 * 정지 상태(P1 {@code SHOP_SUSPENDED})가 필요해지면 그때 메서드·필드를 더한다.
 */
public record ShopRef(long id, String name) {
}
