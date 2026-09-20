package com.bulk.trade.identity.service;

import com.bulk.trade.identity.dto.LoginRequest;
import com.bulk.trade.identity.dto.LoginResponse;

public interface AuthService {

    /**
     * Verifies credentials and issues a token pair.
     *
     * @param clientIp recorded on the account for audit purposes; taken from the
     *                 request, not from the payload
     */
    LoginResponse login(LoginRequest request, String clientIp);

    /** Profile of the caller behind the current token. */
    LoginResponse.UserProfile currentUserProfile();
}
