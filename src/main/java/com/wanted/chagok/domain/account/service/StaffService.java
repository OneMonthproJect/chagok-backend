package com.wanted.chagok.domain.account.service;

import com.wanted.chagok.domain.account.api.StaffReader;
import com.wanted.chagok.domain.account.entity.Staff;
import com.wanted.chagok.domain.account.repository.StaffRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
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
}
