package com.attendenceSystem.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import com.attendenceSystem.constant.Routes;
import com.attendenceSystem.module.faceid.filter.FaceIdApiKeyFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final ActiveUserFilter activeUserFilter;
    private final FaceIdApiKeyFilter faceIdApiKeyFilter;
    private final CustomUserDetailsService userDetailsService;
    private final AuthenticationConfiguration authenticationConfiguration;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                // Token CSRF của Spring tắt (form/fetch hiện có không gửi token); thay bằng OriginCheckFilter
                // + cookie phiên SameSite=Lax (application.properties)
                .csrf(csrf -> csrf.disable())
                .addFilterAfter(new OriginCheckFilter(corsConfigurationSource), CorsFilter.class)
                .authenticationProvider(authenticationProvider())
                .securityContext(context -> context.securityContextRepository(securityContextRepository()))
                .sessionManagement(session -> session
                        .sessionFixation().changeSessionId()
                        .invalidSessionUrl(Routes.Auth.ROOT + Routes.Auth.LOGIN))
                .addFilterBefore(activeUserFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(faceIdApiKeyFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // ------------------- Công khai (không cần đăng nhập) -------------------
                        .requestMatchers("/", "/home").permitAll()
                        .requestMatchers(Routes.Auth.ROOT + "/**", "/login/**").permitAll()
                        // Static resources
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**",
                                "/favicon.ico", "/error").permitAll()
                        // /uploads/** (ảnh khuôn mặt, ảnh bằng chứng, file báo cáo) KHÔNG công khai: cần đăng nhập,
                        // UploadController kiểm tra thêm quyền theo từng loại file
                        // Các API đăng nhập
                        .requestMatchers(Routes.API + Routes.Auth.ROOT + "/**").permitAll()
                        // Gửi/xác thực OTP theo địa chỉ tuỳ ý: chỉ mobile gateway (X-API-Key). Trang web
                        // gửi lại mã qua POST /auth/verify-otp/resend (chỉ gửi tới email có tài khoản).
                        .requestMatchers(Routes.API + Routes.Otp.ROOT + "/**").hasRole(FaceIdApiKeyFilter.ROLE)
                        // ------------------- Face ID -------------------
                        // Chấm công khuôn mặt: chỉ mobile gateway (X-API-Key, xem FaceIdApiKeyFilter)
                        .requestMatchers(Routes.API + Routes.FaceId.ROOT + "/attendance")
                        .hasRole(FaceIdApiKeyFilter.ROLE)
                        // Duyệt hồ sơ khuôn mặt: chỉ quản lý (sidebar chỉ hiện trang này cho MANAGER)
                        .requestMatchers(Routes.FaceId.ROOT,
                                Routes.FaceId.ROOT + Routes.Action.ACCEPT + "/**",
                                Routes.FaceId.ROOT + Routes.Action.REJECT + "/**")
                        .hasAnyRole("ADMIN", "MANAGER")
                        // Tạo hồ sơ khuôn mặt cho userId bất kỳ (form cũ, nhận userId từ client) → chỉ quản lý.
                        // Nhân viên tự đăng ký qua POST /api/face-id/register (luôn gắn với chính họ).
                        .requestMatchers(HttpMethod.POST, Routes.FaceId.ROOT + Routes.Action.CREATE)
                        .hasAnyRole("ADMIN", "MANAGER")
                        // Còn lại (GET /face-id/create, API đăng ký của chính mình...) rơi xuống anyRequest().authenticated()

                        // ------------------- ADMIN -------------------
                        // Danh sách MỌI báo cáo (giao diện không dùng; nhân viên xem my-reports / shared-with-me)
                        .requestMatchers(HttpMethod.GET, Routes.API + Routes.Report.ROOT).hasRole("ADMIN")
                        .requestMatchers(Routes.Role.ADMIN + "/**",
                                Routes.API + Routes.Role.ADMIN + "/**",
                                Routes.System.ROOT + "/**",
                                Routes.API + Routes.System.ROOT + "/**",
                                Routes.Dashboard.ROOT + Routes.Role.ADMIN,
                                Routes.API + Routes.Dashboard.ROOT + Routes.Role.ADMIN)
                        .hasRole("ADMIN")

                        // ------------------- MANAGER -------------------
                        .requestMatchers(Routes.Role.MANAGER + "/**",
                                Routes.API + Routes.Role.MANAGER + "/**",
                                Routes.Dashboard.ROOT + Routes.Role.MANAGER,
                                Routes.API + Routes.Dashboard.ROOT + Routes.Role.MANAGER,
                                Routes.API + Routes.Attendance.ROOT + Routes.Role.MANAGER + "/**")
                        .hasRole("MANAGER")

                        // ------------------- Mọi người dùng đã đăng nhập -------------------
                        .requestMatchers(Routes.Dashboard.ROOT + "/**",
                                Routes.API + Routes.Dashboard.ROOT + "/**",
                                Routes.Attendance.ROOT + "/**",
                                Routes.API + Routes.Attendance.ROOT + "/**",
                                Routes.Schedule.ROOT + "/**",
                                Routes.Report.ROOT + "/**",
                                Routes.API + Routes.Report.ROOT + "/**",
                                Routes.User.ROOT + "/**",
                                Routes.API + Routes.User.ROOT + "/**",
                                Routes.Account.ROOT + "/**")
                        .authenticated()
                        // Mặc định: mọi request khác đều phải đăng nhập
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            // Nếu là API/AJAX -> trả 401
                            if (request.getRequestURI().startsWith("/api/") ||
                                    "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                            } else {
                                // Nếu là request trang bình thường -> chuyển về trang chủ
                                response.sendRedirect(request.getContextPath() + "/");
                            }
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            // Nếu là API/AJAX -> trả 403
                            if (request.getRequestURI().startsWith("/api/") ||
                                    "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
                            } else {
                                // Nếu là request trang bình thường -> chuyển về trang chủ
                                response.sendRedirect(request.getContextPath() + "/");
                            }
                        }));
        return http.build();
    }


    @Bean
    public AuthenticationManager authenticationManager() throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * ActiveUserFilter được đánh dấu là @Component nên Spring Boot sẽ tự động đăng ký nó
     * như một servlet filter. Tuy nhiên ta đã thêm nó vào SecurityFilterChain (addFilterBefore)
     * nên cần tắt auto-registration để tránh chạy 2 lần.
     */
    /** Như ActiveUserFilter: FaceIdApiKeyFilter chỉ chạy trong SecurityFilterChain. */
    @Bean
    public FilterRegistrationBean<FaceIdApiKeyFilter> faceIdApiKeyFilterRegistration(FaceIdApiKeyFilter filter) {
        FilterRegistrationBean<FaceIdApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ActiveUserFilter> activeUserFilterRegistration(ActiveUserFilter filter) {
        FilterRegistrationBean<ActiveUserFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    // Chỉ dùng để test admin
    // @Bean
    // public UserDetailsService userDetailsService(
    // PasswordEncoder passwordEncoder) {

    // UserDetails admin = User.builder()
    // .username("admin")
    // .password(passwordEncoder.encode("123456"))
    // .roles(Role.ADMIN.name())
    // .build();

    // return new InMemoryUserDetailsManager(admin);
    // }
}
