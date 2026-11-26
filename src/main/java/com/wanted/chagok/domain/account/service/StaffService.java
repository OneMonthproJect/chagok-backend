package com.wanted.chagok.domain.account.service;

import com.wanted.chagok.domain.account.api.AssigneeRef;
import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.account.entity.Staff;
import com.wanted.chagok.domain.account.repository.StaffRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StaffService implements StaffReader {

    private final StaffRepository staffRepository;

    public StaffService(StaffRepository staffRepository) {
        this.staffRepository = staffRepository;
    }

    @Override
    public Map<Long, String> findNames(long shopId, Collection<Long> staffIds) {
        if (staffIds == null || staffIds.isEmpty()) {
            return Map.of();
        }
        return staffRepository.findByShopIdAndIdIn(shopId, new HashSet<>(staffIds)).stream()
            .collect(Collectors.toMap(Staff::getId, Staff::getName, (a, b) -> a));
    }

    /** 담당 색 팔레트 크기 (08 7.1: 8색 순환). */
    static final int COLOR_COUNT = 8;

    /**
     * 담당 후보 (API 36). {@code colorIndex}는 그 정비소 직원 전체를 id 순으로 센 순번(0부터)을 8로 나눈 나머지다.
     * 비활성 직원도 순번에 포함해야 해서(08 7.1), 활성만 거른 뒤가 아니라 전체 목록에서 순번을 매긴다.
     */
    @Override
    public List<AssigneeRef> findAssignees(long shopId) {
        // LIMIT: staff.active는 P1(V3)이라 지금은 컬럼이 없고 모든 직원이 활성이다(08 8행). P1에서 비활성 직원은
        // 반환 목록에서만 빼고, 순번(colorIndex)은 전체 기준을 그대로 둔다(08 7.1).
        List<Staff> all = staffRepository.findByShopIdOrderByIdAsc(shopId);
        return IntStream.range(0, all.size())
            .mapToObj(i -> new AssigneeRef(all.get(i).getId(), all.get(i).getName(), all.get(i).getRole(), i % COLOR_COUNT))
            .toList();
    }
}
