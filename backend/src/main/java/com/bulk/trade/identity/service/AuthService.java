package com.bulk.trade.identity.service;

import com.bulk.trade.identity.dto.LoginRequest;
import com.bulk.trade.identity.dto.LoginResponse;

public interface AuthService {

    /**
     * 校验凭据并签发一对令牌。
     *
     * @param clientIp 记在账号上用于留痕；取自请求，而不是取自请求体
     */
    LoginResponse login(LoginRequest request, String clientIp);

    /** 当前令牌背后的调用方资料。 */
    LoginResponse.UserProfile currentUserProfile();
}
