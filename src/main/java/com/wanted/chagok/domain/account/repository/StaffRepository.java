package com.wanted.chagok.domain.account.repository;

import com.wanted.chagok.domain.account.entity.Staff;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffRepository extends JpaRepository<Staff, Long> {

    /** 로그인용. 아이디는 전체에서 유일하다 (07 3.3 uk_staff_login_id). */
    Optional<Staff> findByLoginId(String loginId);

    /** 내 계정용. 정비소 데이터는 토큰의 shopId 조건과 함께만 조회한다 (CONVENTIONS 5장, 07 I-3). */
    Optional<Staff> findByIdAndShopId(Long id, Long shopId);
}
