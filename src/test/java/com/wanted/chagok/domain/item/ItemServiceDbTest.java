package com.wanted.chagok.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wanted.chagok.domain.item.api.ItemReader;
import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.item.dto.ItemListResponse;
import com.wanted.chagok.domain.item.service.ItemService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * ItemReader 구현: Entity ↔ V1 {@code ddl-auto: validate}, 정렬·비활성·정비소 격리(I-3). 앱 전체가 뜨므로 빈 MySQL이 필요하다
 * ({@code ./gradlew dbTest}, CI db-test). 테스트 트랜잭션은 끝나면 롤백한다. 정비소 두 곳과 항목은 이 테스트가 직접 넣는다.
 */
@Tag("db")
@SpringBootTest
@Transactional
class ItemServiceDbTest {

    @Autowired
    private ItemReader reader;

    @Autowired
    private ItemService itemService;

    @Autowired
    private JdbcTemplate jdbc;

    private long shopA;
    private long shopB;
    private long oil;
    private long filter;
    private long retired;
    private long otherShopItem;

    @BeforeEach
    void data() {
        shopA = insertShop("A정비소", "02-000-0001");
        shopB = insertShop("B정비소", "02-000-0002");
        // sortOrder를 id 순서와 다르게 넣어 정렬을 확인한다
        filter = insertItem(shopA, "AIR_FILTER", "에어필터", 12, 20_000, true, 2);
        oil = insertItem(shopA, "ENGINE_OIL", "엔진오일", 6, 10_000, true, 1);
        retired = insertItem(shopA, "WIPER", "와이퍼", 12, 20_000, false, 3);
        otherShopItem = insertItem(shopB, "ENGINE_OIL", "엔진오일", 6, 10_000, true, 1);
    }

    @Test
    @DisplayName("findActiveItems: 그 정비소의 active 항목만 sortOrder 순으로, 값이 그대로 읽힌다")
    void findActiveItems_sortedAndScoped() {
        List<ItemRef> result = reader.findActiveItems(shopA);

        assertThat(result).extracting(ItemRef::itemId).containsExactly(oil, filter);
        ItemRef first = result.get(0);
        assertThat(first.code()).isEqualTo("ENGINE_OIL");
        assertThat(first.name()).isEqualTo("엔진오일");
        assertThat(first.cycleMonths()).isEqualTo(6);
        assertThat(first.cycleKm()).isEqualTo(10_000);
        assertThat(first.active()).isTrue();
        assertThat(first.sortOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("API 13 listItems: 그 정비소 항목 전부(비활성 포함)를 sortOrder 순으로, 남의 정비소 항목은 없다 (X7)")
    void listItems_allOfShop_sortedAndScoped() {
        List<ItemListResponse> result = itemService.listItems(shopA);

        assertThat(result).extracting(ItemListResponse::itemId).containsExactly(oil, filter, retired);
        assertThat(result.get(2).active()).isFalse();
        assertThat(itemService.listItems(shopB)).extracting(ItemListResponse::itemId).containsExactly(otherShopItem);
    }

    @Test
    @DisplayName("findItems: 비활성은 포함, 없는 id·남의 정비소 id는 결과에서 빠진다(오류 아님)")
    void findItems_includesInactive_excludesMissingAndOtherShop() {
        Map<Long, ItemRef> result = reader.findItems(shopA, List.of(oil, retired, otherShopItem, 999_999L));

        assertThat(result).containsOnlyKeys(oil, retired);
        assertThat(result.get(retired).active()).isFalse();
    }

    @Test
    @DisplayName("findItems: 입력이 비어 있으면 빈 Map")
    void findItems_emptyInput() {
        assertThat(reader.findItems(shopA, List.of())).isEmpty();
    }

    @Test
    @DisplayName("requireActiveItems: 모두 쓰는 항목이면 통과, 입력이 비어 있어도 통과, 중복 id도 통과")
    void requireActiveItems_passes() {
        reader.requireActiveItems(shopA, List.of(oil, filter, oil), "purpose.itemIds");
        reader.requireActiveItems(shopA, List.of(), "purpose.itemIds");
    }

    @Test
    @DisplayName("requireActiveItems: 없음·남의 정비소·비활성은 모두 같은 400 INVALID_INPUT이고 field는 넘겨받은 이름")
    void requireActiveItems_rejects() {
        for (long bad : new long[] {999_999L, otherShopItem, retired}) {
            assertThatThrownBy(() -> reader.requireActiveItems(shopA, List.of(oil, bad), "purpose.itemIds"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.fieldErrors()).extracting("field").containsExactly("purpose.itemIds");
                });
        }
    }

    private long insertShop(String name, String phone) {
        jdbc.update("INSERT INTO shop (name, phone, status, created_at) VALUES (?, ?, 'ACTIVE', NOW())", name, phone);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertItem(long shopId, String code, String name, int months, int km, boolean active, int sortOrder) {
        jdbc.update("INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())", shopId, code, name, months, km, active, sortOrder);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
