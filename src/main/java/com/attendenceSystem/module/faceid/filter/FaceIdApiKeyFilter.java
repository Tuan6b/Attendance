package com.attendenceSystem.module.faceid.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Chỗ dành cho việc xác thực API key trên {@code /api/face-id/**} (kiosk điểm danh, đăng ký
 * khuôn mặt...). HIỆN TẠI LÀ PASS-THROUGH — không kiểm tra gì — vì:
 * (1) {@code SecurityConfig} đang để {@code /api/face-id/**} là {@code permitAll}, và
 * (2) frontend hiện tại ({@code attendance-check.js}, {@code faceid-create.js}...) không gửi
 * header API key nào cả — bật kiểm tra ở đây ngay bây giờ sẽ chặn luôn kiosk đang chạy thật.
 *
 * Đây là một rủi ro bảo mật đã biết (bất kỳ ai trên mạng cũng gọi được các API này, xem
 * docs/ONBOARDING.md mục "Rủi ro bảo mật"). Muốn bật lại cần làm cả 2 việc cùng lúc: (a) filter
 * này so sánh header thực tế với {@code face-id.api-key}, và (b) frontend gửi kèm header đó —
 * không chỉ sửa một bên.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FaceIdApiKeyFilter extends OncePerRequestFilter {

    private static final String FACE_ID_PATH_PREFIX = "/api/face-id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(FACE_ID_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        filterChain.doFilter(request, response);
    }
}
