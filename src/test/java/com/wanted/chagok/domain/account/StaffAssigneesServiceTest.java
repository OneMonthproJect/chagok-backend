package com.wanted.chagok.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wanted.chagok.domain.account.api.AssigneeRef;
import com.wanted.chagok.domain.account.entity.Staff;
import com.wanted.chagok.domain.account.repository.StaffRepository;
import com.wanted.chagok.domain.account.service.StaffService;
import com.wanted.chagok.global.auth.StaffRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** API 36 담당 후보와 colorIndex 계산 (08 7.1). DB 없이 돈다. 정비소 격리·실제 조회는 StaffReaderDbTest. */
class StaffAssigneesServiceTest {

    private final StaffRepository repository = mock(StaffRepository.class);
    private final StaffService service = new StaffService(repository);

    private static Staff staff(long id, String name, StaffRole role) {
        Staff staff = BeanUtils.instantiateClass(Staff.class); // 생성자가 protected(setter 없음, CONVENTIONS 5장)
        ReflectionTestUtils.setField(staff, "id", id);
        ReflectionTestUtils.setField(staff, "shopId", 1L);
        ReflectionTestUtils.setField(staff, "name", name);
        ReflectionTestUtils.setField(staff, "role", role);
        return staff;
    }

    @Test
    @DisplayName("id 순서대로 첫 계정이 colorIndex 0, 이름·역할을 그대로 담는다 (08 36 예시)")
    void firstAccountIsZero() {
        when(repository.findByShopIdOrderByIdAsc(1L)).thenReturn(List.of(
            staff(1, "박사장", StaffRole.OWNER), staff(2, "김정비", StaffRole.MECHANIC)));

        assertThat(service.findAssignees(1L)).containsExactly(
            new AssigneeRef(1, "박사장", StaffRole.OWNER, 0),
            new AssigneeRef(2, "김정비", StaffRole.MECHANIC, 1));
    }

    @Test
    @DisplayName("colorIndex는 8색 순환이다: 9번째 계정은 다시 0 (08 7.1, 경계 8·9명)")
    void colorIndexWrapsAfterEight() {
        List<Staff> nine = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            nine.add(staff(100 + i, "직원" + i, i == 1 ? StaffRole.OWNER : StaffRole.MECHANIC));
        }
        when(repository.findByShopIdOrderByIdAsc(1L)).thenReturn(nine);

        assertThat(service.findAssignees(1L)).extracting(AssigneeRef::colorIndex)
            .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 0);
    }

    @Test
    @DisplayName("순번은 계정 id 값이 아니라 그 정비소 안의 순서다 (id가 띄엄띄엄해도 0, 1, 2)")
    void indexIsPositionNotIdValue() {
        when(repository.findByShopIdOrderByIdAsc(2L)).thenReturn(List.of(
            staff(7, "가", StaffRole.OWNER), staff(40, "나", StaffRole.MECHANIC), staff(41, "다", StaffRole.MECHANIC)));

        assertThat(service.findAssignees(2L)).extracting(AssigneeRef::colorIndex).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("직원이 없으면 빈 목록(오류 아님), 조회는 그 정비소 shopId 조건으로만 한다")
    void emptyAndShopCondition() {
        when(repository.findByShopIdOrderByIdAsc(9L)).thenReturn(List.of());

        assertThat(service.findAssignees(9L)).isEmpty();
        verify(repository).findByShopIdOrderByIdAsc(9L);
    }
}
