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
 * Reads the bearer token and populates the security context.
 *
 * <p>An invalid or missing token is not an error here: the filter simply leaves
 * the context empty and lets the authorization rules reject the request later.
 * That keeps a single place responsible for "is this request allowed".
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
                // Who the caller is comes from the token; what they may do comes
                // from the database, because a token cannot be un-issued when a
                // permission is withdrawn.
                if (identity != null) {
                    authenticate(identity, request);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Populates the security context, or declines to.
     *
     * <p><b>A token is a claim about identity, not about authority.</b> Two
     * things can have changed since it was signed, and neither is knowable from
     * the token: the account may have been disabled, and its permissions may
     * have been changed. Both are read now, per request.
     *
     * <p>When either says stop, the context is left empty — the request is not
     * "logged in but forbidden", it is not logged in, and the caller gets 401
     * and a fresh login. For a disabled account that distinction is the whole
     * point: it should stop working now, not in two hours.
     *
     * @return whether the context was populated
     */
    private boolean authenticate(LoginUser identity, HttpServletRequest request) {
        UserAuthority authority;
        try {
            authority = authorityProvider.load(identity.getUserId());
        } catch (RuntimeException e) {
            // Reading authority must not be able to fail a request into an
            // unauthenticated state. If it throws, the safer reading is "no
            // session", which is what the caller gets — a 401 they can act on,
            // rather than a 500 they cannot.
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
     * Finds the bearer token.
     *
     * <p>Normally the Authorization header. The streaming endpoints are the
     * exception: the browser's EventSource API cannot set headers, so those
     * also accept the token as a query parameter.
     *
     * <p>This is a real trade-off and not a free one — a token in a URL can end
     * up in access logs, browser history and proxy logs. So the exception is a
     * <b>named list of paths</b>, never a rule like "anything containing
     * stream": widening it by accident is exactly how the token ends up in a
     * log line nobody thought about. The proper fix (a short-lived, single-use
     * stream ticket) is noted rather than pretended away.
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
