package com.wanted.chagok.domain.shop.api;

import java.util.Optional;

/**
 * 다른 도메인이 정비소를 읽는 유일한 통로 (CONVENTIONS 1장 1번). {@code account}가 로그인·내 계정 응답의 정비소명을 얻는 데 쓴다
 * (CONVENTIONS 3장: account → shop.api).
 */
public interface ShopReader {

    /** 정비소가 없으면 빈 값. 정비소 소속 확인(격리)이 아니라 이미 확정된 {@code shopId}의 표시값 조회다. */
    Optional<ShopRef> findById(long shopId);
}
