package com.wanted.chagok.domain.account.service;

import com.wanted.chagok.domain.account.dto.AdminLoginResponse;
import com.wanted.chagok.domain.account.dto.AdminLoginResponse.AdminInfo;
import com.wanted.chagok.domain.account.dto.LoginRequest;
import com.wanted.chagok.domain.account.dto.LoginResponse;
import com.wanted.chagok.domain.account.dto.MeResponse;
import com.wanted.chagok.domain.account.entity.PlatformAdmin;
import com.wanted.chagok.domain.account.entity.Staff;
import com.wanted.chagok.domain.account.repository.PlatformAdminRepository;
import com.wanted.chagok.domain.account.repository.StaffRepository;
import com.wanted.chagok.domain.shop.api.ShopReader;
import com.wanted.chagok.global.auth.JwtIssuer;
import com.wanted.chagok.global.auth.JwtIssuer.IssuedToken;
import com.wanted.chagok.global.auth.LoginStaff;
import com.wanted.chagok.global.auth.TokenClaims;
import com.wanted.chagok.global.error.BusinessException;
import com.wanted.chagok.global.error.ErrorCode;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인(01·02)과 내 계정(03). 근거: 04 F-AUTH-01·02, 08 01~03.
 *
 * <p>로그인 실패는 {@code LOGIN_FAILED} 하나다. 아이디가 없는 경우와 비밀번호가 틀린 경우를 응답으로도, 응답 시간으로도
 * 구분하지 못하게 한다(아이디가 없어도 가짜 해시와 비교를 한 번 한다). 사장·정비사 아이디는 02로, 서비스 관리자 아이디는 01로
 * 로그인할 수 없다 — 표가 다르다(07 3.1·3.3). 비밀번호·토큰은 로그에 남기지 않는다.
 *
 * <p>LIMIT: 로그인 시도 횟수 제한(잠금·지연)이 없다. P0 범위 밖이며, 필요해지면 계정 잠금 정책을 결정 이슈로 정한다.
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

    private final StaffRepository staffRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final ShopReader shopReader;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    /** 없는 아이디일 때도 같은 비용의 비교를 하려고 쓰는 가짜 해시. 어떤 계정과도 맞지 않는다. */
    private final String dummyHash;

    public AuthService(StaffRepository staffRepository, PlatformAdminRepository platformAdminRepository,
        ShopReader shopReader, PasswordEncoder passwordEncoder, JwtIssuer jwtIssuer) {
        this.staffRepository = staffRepository;
        this.platformAdminRepository = platformAdminRepository;
        this.shopReader = shopReader;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** API 01. */
    public LoginResponse login(LoginRequest request) {
        Staff staff = staffRepository.findByLoginId(request.loginId()).orElse(null);
        if (!passwordMatches(request.password(), staff == null ? null : staff.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        IssuedToken token = jwtIssuer.issueForStaff(staff.getId(), staff.getShopId(), staff.getRole());
        return new LoginResponse(token.accessToken(), LoginResponse.BEARER, token.expiresInSeconds(), toMe(staff));
    }

    /** API 02. 토큰에 shopId가 없다 (07 I-5). */
    public AdminLoginResponse adminLogin(LoginRequest request) {
        PlatformAdmin admin = platformAdminRepository.findByLoginId(request.loginId()).orElse(null);
        if (!passwordMatches(request.password(), admin == null ? null : admin.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        IssuedToken token = jwtIssuer.issueForAdmin(admin.getId());
        return new AdminLoginResponse(token.accessToken(), LoginResponse.BEARER, token.expiresInSeconds(),
            new AdminInfo(admin.getId(), admin.getName(), TokenClaims.ADMIN_ROLE));
    }

    /**
     * API 03. 계정은 토큰의 staffId·shopId로만 찾는다(요청 값 없음). 토큰은 유효한데 계정이 없으면 다시 로그인하게 401로 답한다.
     * 근거: 08 0.6 표 1행 "토큰의 staffId에 해당하는 staff 행이 없음 → 401 AUTH_REQUIRED". 0.6은 P1 계정 상태 확인이지만
     * 이 경우의 응답은 같아서 P0의 03에도 같은 응답을 쓴다 (비활성·비밀번호 변경·약관 상태 확인은 P1).
     */
    public MeResponse me(LoginStaff login) {
        Staff staff = staffRepository.findByIdAndShopId(login.staffId(), login.shopId())
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        return toMe(staff);
    }

    private MeResponse toMe(Staff staff) {
        // staff.shop_id는 shop을 가리키는 FK라 정비소가 없을 수 없다. 없다면 데이터 이상이므로 서버 오류다.
        String shopName = shopReader.findById(staff.getShopId())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR))
            .name();
        return new MeResponse(staff.getId(), staff.getName(), staff.getRole(), staff.getShopId(), shopName);
    }

    private boolean passwordMatches(String rawPassword, String storedHash) {
        boolean hasAccount = storedHash != null;
        boolean matched = passwordEncoder.matches(rawPassword, hasAccount ? storedHash : dummyHash);
        return hasAccount && matched;
    }
}
