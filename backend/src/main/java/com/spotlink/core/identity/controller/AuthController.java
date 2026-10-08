package com.spotlink.identity.controller;

import com.spotlink.identity.dto.LoginRequest;
import com.spotlink.identity.dto.LoginResponse;
import com.spotlink.identity.service.AuthService;
import com.spotlink.shared.web.ApiResponse;
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
     * 解析调用方的地址，有代理头时以代理头为准。
     *
     * <p>这里只信任 {@code X-Forwarded-For} 的第一项，因为部署位于单个反向代理之后。
     * 如果能被不受信任的客户端直连到应用，这个头就是可伪造的，不能用于任何与安全相关
     * 的判断——它只用于留痕。
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
