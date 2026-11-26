package com.wanted.chagok.domain.account.dto;

import com.wanted.chagok.domain.account.api.AssigneeRef;
import com.wanted.chagok.global.auth.StaffRole;
import java.util.List;

/**
 * API 36 응답 {@code data} (08 7.1): {@code items: [ { staffId, name, role, colorIndex } ]}. 쪽 나누기 없음.
 * account.api의 {@link AssigneeRef}를 응답 모양으로만 옮긴다.
 */
public record AssigneeListResponse(List<Item> items) {

    public record Item(long staffId, String name, StaffRole role, int colorIndex) {

        static Item from(AssigneeRef ref) {
            return new Item(ref.staffId(), ref.name(), ref.role(), ref.colorIndex());
        }
    }

    public static AssigneeListResponse from(List<AssigneeRef> refs) {
        return new AssigneeListResponse(refs.stream().map(Item::from).toList());
    }
}
