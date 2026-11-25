package com.wanted.chagok.domain.shop.service;

import com.wanted.chagok.domain.shop.api.ShopReader;
import com.wanted.chagok.domain.shop.api.ShopRef;
import com.wanted.chagok.domain.shop.repository.ShopRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ShopReader} 구현. 다른 도메인은 이 클래스가 아니라 {@code shop.api}만 본다. */
@Service
@Transactional(readOnly = true)
public class ShopService implements ShopReader {

    private final ShopRepository shopRepository;

    public ShopService(ShopRepository shopRepository) {
        this.shopRepository = shopRepository;
    }

    @Override
    public Optional<ShopRef> findById(long shopId) {
        return shopRepository.findById(shopId).map(shop -> new ShopRef(shop.getId(), shop.getName()));
    }
}
