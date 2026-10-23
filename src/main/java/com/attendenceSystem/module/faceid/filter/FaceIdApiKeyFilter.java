package com.attendenceSystem.module.faceid.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Xác thực mobile gateway trên {@code POST /api/face-id/attendance} bằng header {@code X-API-Key}
 * (= {@code face-id.api-key}, bên gateway là ATTENDANCE_API_KEY). Key đúng → request này mang
 * {@code ROLE_FACE_GATEWAY} (SecurityConfig chỉ cho role đó chấm công); key sai → 401 luôn; không
 * có header → đi tiếp như request thường (SecurityConfig sẽ chặn).
 *
 * <p>Nằm TRONG chuỗi Spring Security (SecurityConfig.addFilterBefore, đăng ký servlet tự động bị
 * tắt): đặt ngoài chuỗi thì SecurityContextHolderFilter sẽ ghi đè authentication đặt ở đây.
 * Authentication chỉ sống trong request, không lưu vào session.
 */
@Component
public class FaceIdApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String ROLE = "FACE_GATEWAY";
    private static final String ATTENDANCE_PATH = "/api/face-id/attendance";
    private static final int MIN_KEY_LENGTH = 32;

    private final byte[] apiKey;

    public FaceIdApiKeyFilter(@Value("${face-id.api-key}") String apiKey) {
        if (apiKey == null || apiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("FACE_ID_API_KEY phải dài >= " + MIN_KEY_LENGTH + " ký tự");
        }
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ATTENDANCE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), apiKey)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "face-gateway", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }
}
