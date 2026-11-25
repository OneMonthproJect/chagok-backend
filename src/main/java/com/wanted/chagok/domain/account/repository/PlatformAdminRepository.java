package com.wanted.chagok.domain.account.repository;

import com.wanted.chagok.domain.account.entity.PlatformAdmin;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {

    /** 서비스 관리자 로그인용. 아이디는 유일하다 (07 3.1 uk_platform_admin_login_id). */
    Optional<PlatformAdmin> findByLoginId(String loginId);
}
