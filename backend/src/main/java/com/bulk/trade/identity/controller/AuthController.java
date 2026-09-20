package com.bulk.trade.identity.controller;

import com.bulk.trade.identity.dto.LoginRequest;
import com.bulk.trade.identity.dto.LoginResponse;
import com.bulk.trade.identity.service.AuthService;
import com.bulk.trade.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证", description = "登录与当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "账号密码登录")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest servletRequest) {
        return ApiResponse.success(authService.login(request, resolveClientIp(servletRequest)));
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    public ApiResponse<LoginResponse.UserProfile> currentUser() {
        return ApiResponse.success(authService.currentUserProfile());
    }

    /**
     * Resolves the caller's address, honouring a proxy header when present.
     *
     * <p>Only the first entry of {@code X-Forwarded-For} is trusted here because
     * the deployment sits behind a single reverse proxy. With an untrusted
     * client able to reach the app directly, this header is spoofable and must
     * not be used for anything security-relevant — it is recorded for audit
     * only.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
