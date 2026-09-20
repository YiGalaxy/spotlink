package com.bulk.trade.shared.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 读取持有者令牌并填充安全上下文。
 *
 * <p>令牌无效或缺失在这里不算错误：过滤器只是让上下文保持为空，留给后面的授权规则去
 * 拒绝这个请求。这样「这个请求是否被允许」就只有一处负责。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserAuthorityProvider authorityProvider;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            Claims claims = tokenProvider.parse(token);
            if (claims != null) {
                LoginUser identity = tokenProvider.toLoginUser(claims);
                // 调用方是谁来自令牌；能做什么来自数据库，因为权限被收回时，
                // 令牌是无法被撤回的。
                if (identity != null) {
                    authenticate(identity, request);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 填充安全上下文，或者决定不填。
     *
     * <p><b>令牌是对身份的声明，不是对权限的声明。</b>从它签发到现在，有两件事可能已经变了，
     * 而这两件都无法从令牌本身得知：账号可能已被禁用，权限可能已被改动。现在这两者都按请求
     * 实时读取。
     *
     * <p>只要其中任何一项说不，上下文就保持为空——这个请求不是「已登录但被禁止」，而是
     * **根本没有登录**，调用方拿到 401 并重新登录。对一个被禁用的账号来说，这个区别就是
     * 全部意义所在：它应当**现在**就失效，而不是两小时后。
     *
     * @return 上下文是否已被填充
     */
    private boolean authenticate(LoginUser identity, HttpServletRequest request) {
        UserAuthority authority;
        try {
            authority = authorityProvider.load(identity.getUserId());
        } catch (RuntimeException e) {
            // 读取权限这件事，绝不能把一个请求失败成未认证状态。它若抛异常，
            // 更安全的解读是「没有会话」，调用方拿到的也正是这个——一个他能
            // 据此行动的 401，而不是一个他无法行动的 500。
            log.error("Could not resolve authority for user {}", identity.getUserId(), e);
            return false;
        }
        if (authority == null || !authority.isActive()) {
            log.debug("Token for user {} declined: {}",
                    identity.getUserId(),
                    authority == null ? "account no longer exists" : "account not active");
            return false;
        }

        LoginUser loginUser = identity.withAuthority(authority);
        var authentication = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return true;
    }

    /**
     * 找出持有者令牌。
     *
     * <p>通常来自 Authorization 请求头。流式端点是例外：浏览器的 EventSource API 无法设置
     * 请求头，所以那些端点也接受把令牌放在查询参数里。
     *
     * <p>这是一个真实的取舍，不是免费的——URL 里的令牌可能落进访问日志、浏览器历史和代理
     * 日志。所以例外是一份<b>具名的路径清单</b>，绝不是「任何包含 stream 的路径」这类规则：
     * 一次意外的放宽，正是令牌出现在某条谁也没想过的日志行里的方式。真正的修法（短时效、
     * 一次性的流票据）被记下来，而不是被装作不存在。
     */
    private static final List<String> TOKEN_IN_QUERY_PATHS = List.of(
            "/market/stream",
            "/tasks/stream");

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        String uri = request.getRequestURI();
        for (String path : TOKEN_IN_QUERY_PATHS) {
            if (uri.endsWith(path)) {
                return request.getParameter("token");
            }
        }
        return null;
    }
}
