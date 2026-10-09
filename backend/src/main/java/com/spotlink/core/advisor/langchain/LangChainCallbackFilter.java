package com.spotlink.advisor.langchain;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** 在 JSON 反序列化之前拒绝普通令牌并限制请求体；支持无 Content-Length 的请求。 */
@Component
@RequiredArgsConstructor
public class LangChainCallbackFilter extends OncePerRequestFilter {
    private final LangChainGateway gateway;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/advisor/langchain/")
                || (request.getRequestURI().equals("/internal/advisor/langchain/key") && request.getMethod().equals("GET"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try { gateway.authorizeCallback(request.getHeader("Authorization")); }
        catch (Exception failure) { error(response, 403); return; }
        byte[] body = request.getInputStream().readNBytes(65537);
        if (body.length > 65536) { error(response, 413); return; }
        var wrapped = new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var stream = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return stream.read(); }
                    @Override public boolean isFinished() { return stream.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        };
        chain.doFilter(wrapped, response);
    }
    private static void error(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Invalid or oversized delegation request\"}");
    }
}
