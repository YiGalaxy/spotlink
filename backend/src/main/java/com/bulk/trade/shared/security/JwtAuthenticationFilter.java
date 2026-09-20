package com.bulk.trade.shared.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the bearer token and populates the security context.
 *
 * <p>An invalid or missing token is not an error here: the filter simply leaves
 * the context empty and lets the authorization rules reject the request later.
 * That keeps a single place responsible for "is this request allowed".
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            Claims claims = tokenProvider.parse(token);
            if (claims != null) {
                LoginUser loginUser = tokenProvider.toLoginUser(claims);
                var authentication = new UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Finds the bearer token.
     *
     * <p>Normally the Authorization header. The market stream is the one
     * exception: the browser's EventSource API cannot set headers, so that
     * endpoint also accepts the token as a query parameter.
     *
     * <p>This is a real trade-off and not a free one — a token in a URL can end
     * up in access logs, browser history and proxy logs. It is confined to the
     * single streaming path, and the proper fix (a short-lived, single-use
     * stream ticket) is noted rather than pretended away.
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        if (request.getRequestURI().endsWith("/market/stream")) {
            return request.getParameter("token");
        }
        return null;
    }
}
