package com.bulk.trade.identity.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.identity.dto.LoginRequest;
import com.bulk.trade.identity.dto.LoginResponse;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.entity.User;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.identity.mapper.UserMapper;
import com.bulk.trade.identity.service.AuthService;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.security.JwtTokenProvider;
import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    @Value("${bulk.security.jwt.access-token-ttl}")
    private Duration accessTokenTtl;

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, request.username()));

        // "No such user" and "wrong password" deliberately return the same code.
        // Distinct messages would let an attacker enumerate valid usernames,
        // and on a trading platform a username list is a customer list.
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("Failed login attempt for username='{}' from {}", request.username(), clientIp);
            throw BusinessException.of(ResultCode.LOGIN_FAILED);
        }

        if (user.getStatus() != null && user.getStatus() == User.Status.DISABLED) {
            throw BusinessException.of(ResultCode.ACCOUNT_DISABLED);
        }
        if (user.getStatus() != null && user.getStatus() == User.Status.LOCKED) {
            throw BusinessException.of(ResultCode.ACCOUNT_LOCKED);
        }

        Enterprise enterprise = loadAndValidateEnterprise(user);

        LoginUser loginUser = LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .enterpriseId(user.getEnterpriseId())
                .userType(user.getUserType())
                .status(user.getStatus())
                .build();

        LoginResponse response = new LoginResponse(
                tokenProvider.createAccessToken(loginUser),
                tokenProvider.createRefreshToken(loginUser),
                accessTokenTtl.toSeconds(),
                toProfile(user, enterprise));

        recordLogin(user.getId(), clientIp);
        log.info("User '{}' logged in, enterpriseId={}", user.getUsername(), user.getEnterpriseId());
        return response;
    }

    @Override
    public LoginResponse.UserProfile currentUserProfile() {
        LoginUser current = SecurityUtils.currentUser();
        User user = userMapper.selectById(current.getUserId());
        if (user == null) {
            // The account was deleted after the token was issued.
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Enterprise enterprise = loadAndValidateEnterprise(user);
        return toProfile(user, enterprise);
    }

    /**
     * A tenant account is only usable while its enterprise is approved.
     * Checking this on every login (not only at registration) is what makes a
     * frozen enterprise actually stop trading.
     */
    private Enterprise loadAndValidateEnterprise(User user) {
        if (user.getEnterpriseId() == null) {
            return null;
        }
        Enterprise enterprise = enterpriseMapper.selectById(user.getEnterpriseId());
        if (enterprise == null) {
            throw BusinessException.of(ResultCode.ENTERPRISE_NOT_FOUND);
        }
        if (enterprise.getStatus() != null) {
            if (enterprise.getStatus() == Enterprise.Status.FROZEN) {
                throw BusinessException.of(ResultCode.ENTERPRISE_FROZEN);
            }
            if (enterprise.getStatus() != Enterprise.Status.APPROVED) {
                throw BusinessException.of(ResultCode.ENTERPRISE_NOT_APPROVED);
            }
        }
        return enterprise;
    }

    private void recordLogin(Long userId, String clientIp) {
        User update = new User();
        update.setId(userId);
        update.setLastLoginAt(OffsetDateTime.now());
        update.setLastLoginIp(clientIp);
        userMapper.updateById(update);
    }

    private LoginResponse.UserProfile toProfile(User user, Enterprise enterprise) {
        return new LoginResponse.UserProfile(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getEnterpriseId(),
                enterprise == null ? null : enterprise.getName(),
                enterprise == null ? null : enterprise.getTraderCode(),
                user.getUserType(),
                user.getEnterpriseId() == null);
    }
}
