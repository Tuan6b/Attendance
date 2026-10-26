package com.attendenceSystem.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginCheckFilterTest {

    private final OriginCheckFilter filter = new OriginCheckFilter(new CorsConfig().corsConfigurationSource());

    @Test
    void sameHostOriginPasses() throws Exception {
        assertEquals(200, post("Origin", "https://attendance.local:9090"));
    }

    @Test
    void foreignOriginIsBlocked() throws Exception {
        assertEquals(403, post("Origin", "https://evil.example"));
        assertEquals(403, post("Origin", "null"));
    }

    @Test
    void foreignRefererIsBlockedWhenOriginMissing() throws Exception {
        assertEquals(403, post("Referer", "https://evil.example/page"));
        assertEquals(200, post("Referer", "http://attendance.local:9090/auth/login"));
    }

    @Test
    void corsAllowlistedOriginPasses() throws Exception {
        assertEquals(200, post("Origin", "http://localhost:5000"));
    }

    @Test
    void serverToServerCallsWithoutBrowserHeadersPass() throws Exception {
        assertEquals(200, post(null, null)); // mobile gateway
    }

    @Test
    void safeMethodsAreNotChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
        request.addHeader("Host", "attendance.local:9090");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
    }

    private int post(String header, String value) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader("Host", "attendance.local:9090");
        if (header != null) {
            request.addHeader(header, value);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
