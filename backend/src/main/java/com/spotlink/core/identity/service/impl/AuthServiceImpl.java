package com.spotlink.identity.service.impl;

import com.spotlink.identity.dto.LoginRequest;
import com.spotlink.identity.dto.LoginResponse;
import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.entity.User;
import com.spotlink.identity.mapper.EnterpriseMapper;
import com.spotlink.identity.mapper.UserMapper;
import com.spotlink.identity.service.AuthService;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.JwtTokenProvider;
import com.spotlink.shared.security.UserAuthority;
import com.spotlink.shared.security.UserAuthorityProvider;
import java.util.List;
import java.util.Set;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.shared.web.ResultCode;
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
    private final UserAuthorityProvider authorityProvider;
    private final JwtTokenProvider tokenProvider;

    @Value("${bulk.security.jwt.access-token-ttl}")
    private Duration accessTokenTtl;

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
        User user = userMapper.findByUsername(request.username());

        // 「没有这个用户」和「密码错误」刻意返回同一个错误码。区分开的提示会让攻击者能够
        // 枚举出有效用户名，而在一个交易平台上，一份用户名清单就是一份客户清单。
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

        // 权限在这里加载一次，之后每个请求还会再加载，这样登录响应就能带上它，
        // 控制台不必再发第二次请求就能渲染菜单。此后每个请求都会重新加载，因为这份副本
        // 在角色发生变化的那一刻就过期了。
        UserAuthority authority = authorityProvider.load(user.getId());

        LoginUser loginUser = LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .enterpriseId(user.getEnterpriseId())
                .userType(user.getUserType())
                .status(user.getStatus())
                .permissions(authority == null ? Set.of() : authority.permissions())
                .build();

        LoginResponse response = new LoginResponse(
                tokenProvider.createAccessToken(loginUser),
                tokenProvider.createRefreshToken(loginUser),
                accessTokenTtl.toSeconds(),
                toProfile(user, enterprise, authority));

        recordLogin(user.getId(), clientIp);
        log.info("User '{}' logged in, enterpriseId={}", user.getUsername(), user.getEnterpriseId());
        return response;
    }

    @Override
    public LoginResponse.UserProfile currentUserProfile() {
        LoginUser current = SecurityUtils.currentUser();
        User user = userMapper.selectById(current.getUserId());
        if (user == null) {
            // 账号在 token 签发之后被删掉了。
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Enterprise enterprise = loadAndValidateEnterprise(user);
        return toProfile(user, enterprise, authorityProvider.load(user.getId()));
    }

    /**
     * 租户账号只有在其企业已通过审核期间才可用。
     * 每次登录都做这项检查（而不是只在注册时做）才让一个被冻结的企业真正停止交易。
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

    /**
     * @param authority 调用方的实时权限；账号解析不出来时为 null —— 此时档案仍然照常渲染，
     *                  只是不带任何权限。在这里失败会让一个可选字段有能力弄挂整个登录。
     */
    private LoginResponse.UserProfile toProfile(User user, Enterprise enterprise,
                                               UserAuthority authority) {
        return new LoginResponse.UserProfile(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getEnterpriseId(),
                enterprise == null ? null : enterprise.getName(),
                enterprise == null ? null : enterprise.getTraderCode(),
                user.getUserType(),
                user.getEnterpriseId() == null,
                authority == null ? List.of() : List.copyOf(authority.permissions()),
                List.copyOf(authorityProvider.rolesOf(user.getId())));
    }
}
