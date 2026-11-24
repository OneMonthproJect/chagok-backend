package com.wanted.chagok.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.item.api.ItemRef;
import com.wanted.chagok.domain.item.entity.ServiceItem;
import com.wanted.chagok.domain.item.repository.ServiceItemRepository;
import com.wanted.chagok.domain.item.service.ItemService;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code ItemService} 단위 테스트 — Repository는 가짜(DB 없음). shopId 전달, 한 번의 조회, 빈 입력, 오류 모양을 본다.
 * 정렬·Entity 매핑(validate)·정비소 격리 쿼리는 DB가 필요해 {@code ItemServiceDbTest}(dbTest)에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    private static final long SHOP = 7L;

    @Mock
    private ServiceItemRepository repository;

    private ItemService service() {
        return new ItemService(repository);
    }

    private static ServiceItem item(long id, boolean active) {
        ServiceItem item = newInstance();
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "shopId", SHOP);
        ReflectionTestUtils.setField(item, "code", "CODE_" + id);
        ReflectionTestUtils.setField(item, "name", "항목" + id);
        ReflectionTestUtils.setField(item, "cycleMonths", (short) 6);
        ReflectionTestUtils.setField(item, "cycleKm", 10_000);
        ReflectionTestUtils.setField(item, "active", active);
        ReflectionTestUtils.setField(item, "sortOrder", (short) id);
        return item;
    }

    private static ServiceItem newInstance() {
        try {
            var ctor = ServiceItem.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("findItems: shopId와 함께 한 번만 조회하고 id→ItemRef로 바꾼다(비활성 포함)")
    void findItems_oneQueryWithShopId() {
        when(repository.findByShopIdAndIdIn(SHOP, Set.of(1L, 2L))).thenReturn(List.of(item(1, true), item(2, false)));

        Map<Long, ItemRef> result = service().findItems(SHOP, List.of(1L, 2L, 1L));

        assertThat(result).containsOnlyKeys(1L, 2L);
        assertThat(result.get(2L).active()).isFalse();
        assertThat(result.get(1L).cycleMonths()).isEqualTo(6);
        assertThat(result.get(1L).cycleKm()).isEqualTo(10_000);
        verify(repository).findByShopIdAndIdIn(SHOP, Set.of(1L, 2L));
    }

    @Test
    @DisplayName("findItems·requireActiveItems: 입력이 비면 조회하지 않고 빈 Map·통과")
    void emptyInput_noQuery() {
        assertThat(service().findItems(SHOP, List.of())).isEmpty();
        service().requireActiveItems(SHOP, List.of(), "purpose.itemIds");

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("requireActiveItems: 일부가 없거나 비활성이면 400 INVALID_INPUT, field는 넘겨받은 이름")
    void requireActiveItems_rejectsMissingOrInactive() {
        when(repository.findByShopIdAndIdIn(anyLong(), any())).thenReturn(List.of(item(1, true), item(2, false)));

        for (List<Long> ids : List.of(List.of(1L, 3L), List.of(1L, 2L))) {
            assertThatThrownBy(() -> service().requireActiveItems(SHOP, ids, "records[0].itemId"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.fieldErrors()).extracting("field").containsExactly("records[0].itemId");
                });
        }
    }

    @Test
    @DisplayName("requireActiveItems: 모두 쓰는 항목이면 통과")
    void requireActiveItems_passes() {
        when(repository.findByShopIdAndIdIn(anyLong(), any())).thenReturn(List.of(item(1, true), item(2, true)));

        service().requireActiveItems(SHOP, List.of(1L, 2L, 2L), "purpose.itemIds");
    }

    @Test
    @DisplayName("findActiveItems: shopId로 조회한 순서 그대로 ItemRef로 바꾼다")
    void findActiveItems_mapsInRepositoryOrder() {
        when(repository.findByShopIdAndActiveTrueOrderBySortOrderAscIdAsc(SHOP)).thenReturn(List.of(item(2, true), item(1, true)));

        assertThat(service().findActiveItems(SHOP)).extracting(ItemRef::itemId).containsExactly(2L, 1L);
    }
}
