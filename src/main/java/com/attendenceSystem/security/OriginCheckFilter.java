package com.attendenceSystem.security;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Chống CSRF thay cho token của Spring Security (đang tắt vì hàng loạt form/fetch trong templates không
 * gửi token): request làm thay đổi dữ liệu (POST/PUT/PATCH/DELETE) mà trình duyệt gửi kèm {@code Origin}
 * (hoặc {@code Referer}) khác host của chính trang này và không nằm trong CORS allowlist → 403.
 * Theo OWASP "Verifying origin with standard headers", dùng cùng cookie phiên SameSite=Lax.
 *
 * <ul>
 * <li>So authority (host[:port]) với header Host, bỏ qua scheme — sau reverse proxy TLS scheme khác nhau.</li>
 * <li>{@code Origin: null} (iframe sandbox, redirect chéo) → chặn.</li>
 * <li>Không có cả Origin lẫn Referer → cho qua: không phải trình duyệt (mobile gateway gọi server-to-server).</li>
 * </ul>
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final CorsConfigurationSource corsConfigurationSource;

    public OriginCheckFilter(CorsConfigurationSource corsConfigurationSource) {
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String source = request.getHeader("Origin");
        if (source == null) {
            source = request.getHeader("Referer");
        }
        if (SAFE_METHODS.contains(request.getMethod()) || source == null || isAllowed(source, request)) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-site request blocked");
    }

    private boolean isAllowed(String source, HttpServletRequest request) {
        URI uri;
        try {
            uri = URI.create(source);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String authority = uri.getRawAuthority();
        if (authority == null || uri.getScheme() == null) {
            return false; // gồm cả "null"
        }
        String host = request.getHeader("Host");
        if (host == null) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        if (authority.equalsIgnoreCase(host)) {
            return true;
        }
        CorsConfiguration cors = corsConfigurationSource.getCorsConfiguration(request);
        return cors != null && cors.checkOrigin(uri.getScheme() + "://" + authority) != null;
    }
}
