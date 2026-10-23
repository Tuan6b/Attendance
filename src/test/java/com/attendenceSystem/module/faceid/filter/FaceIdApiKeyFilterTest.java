package com.attendenceSystem.module.faceid.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class FaceIdApiKeyFilterTest {

    private static final String KEY = "k".repeat(40);

    private final FaceIdApiKeyFilter filter = new FaceIdApiKeyFilter(KEY);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void correctKeyAuthenticatesAsGateway() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        MockHttpServletResponse response = run(KEY, seen);

        assertEquals(200, response.getStatus());
        assertNotNull(seen.get());
        assertEquals("ROLE_" + FaceIdApiKeyFilter.ROLE, seen.get().getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void wrongKeyIsRejectedBeforeController() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        MockHttpServletResponse response = run(KEY + "x", seen);

        assertEquals(401, response.getStatus());
        assertNull(seen.get());
    }

    @Test
    void missingKeyPassesThroughUnauthenticated() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        MockHttpServletResponse response = run(null, seen);

        assertEquals(200, response.getStatus());
        assertNull(seen.get()); // SecurityConfig sẽ trả 401 vì thiếu ROLE_FACE_GATEWAY
    }

    @Test
    void shortKeyFailsStartup() {
        assertThrows(IllegalStateException.class, () -> new FaceIdApiKeyFilter("too-short"));
    }

    /** Chạy filter cho POST /api/face-id/attendance; seen = authentication mà controller nhìn thấy (null nếu không tới). */
    private MockHttpServletResponse run(String key, AtomicReference<Authentication> seen) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/face-id/attendance");
        if (key != null) {
            request.addHeader(FaceIdApiKeyFilter.HEADER, key);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return response;
    }
}
