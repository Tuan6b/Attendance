package com.attendenceSystem.module.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestMapping;

import com.attendenceSystem.constant.Routes;
import com.attendenceSystem.constant.Views;
import com.attendenceSystem.exception.custom.ConflictException;
import com.attendenceSystem.exception.custom.ResourceNotFoundException;
import com.attendenceSystem.module.otp.dto.request.SendOtpRequest;
import com.attendenceSystem.module.otp.entity.enums.OtpPurpose;
import com.attendenceSystem.module.otp.service.OtpService;
import com.attendenceSystem.module.user.dto.request.LoginRequest;
import com.attendenceSystem.module.user.dto.request.RegisterRequest;
import com.attendenceSystem.module.user.dto.request.UpdatePasswordWithOtpRequest;
import com.attendenceSystem.module.user.dto.response.UserResponse;
import com.attendenceSystem.module.user.exception.EmailNotVerifiedException;
import com.attendenceSystem.module.user.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping(Routes.Auth.ROOT)
@RequiredArgsConstructor
public class AuthController {
    // Email thật của tài khoản "login trước, phát hiện chưa xác thực" chỉ nằm ở session
    // server-side — KHÔNG bao giờ đưa vào flash/model/hidden field cho case này, để tránh
    // lộ email thật của người khác cho ai đó chỉ mới đoán đúng username (xem login()).
    private static final String SESSION_PENDING_VERIFY_EMAIL = "pendingVerifyEmail";

    private final AuthService authService;
    private final OtpService otpService;

    @GetMapping(Routes.Auth.LOGIN)
    public String toLoginPage(Model model) {
        model.addAttribute("loginRequest", new LoginRequest());
        return Views.Auth.LOGIN;
    }

    @GetMapping(Routes.Auth.REGISTER)
    public String toRegisterPage(Model model) {
        model.addAttribute("registerRequest", new RegisterRequest());
        return Views.Auth.REGISTER;
    }

    @GetMapping(Routes.Auth.FORGOT_PASSWORD)
    public String toForgotPasswordPage() {
        return Views.Auth.FORGOT_PASSWORD;
    }

    @GetMapping(Routes.Auth.VERIFY_OTP)
    public String toVerifyOtpPage(
            @RequestParam(required = false) String email,
            @RequestParam(required = false, defaultValue = "FORGOT_PASSWORD") String purpose,
            HttpServletRequest request,
            Model model) {
        // email/purpose thường đến từ flash attribute (redirect từ register/forgotPassword);
        // chỉ dùng query param làm phương án dự phòng khi truy cập/tải lại trang trực tiếp.
        if (!model.containsAttribute("email") && email != null) {
            model.addAttribute("email", email);
        }
        if (!model.containsAttribute("email")) {
            // Tới từ login() phát hiện chưa xác thực: email thật chỉ nằm trong session,
            // không đưa vào model/HTML — trang chỉ biết "có một email đang chờ xác thực".
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(SESSION_PENDING_VERIFY_EMAIL) != null) {
                model.addAttribute("emailPending", true);
            }
        }
        if (!model.containsAttribute("purpose")) {
            model.addAttribute("purpose", purpose);
        }
        return Views.Auth.VERIFY_OTP;
    }

    @GetMapping(Routes.Auth.CHANGE_PASSWORD)
    public String toChangePasswordPage(Model model, @RequestParam("email") String email) {
        UpdatePasswordWithOtpRequest request = new UpdatePasswordWithOtpRequest();
        request.setDestination(email);
        model.addAttribute("request", request);
        return Views.Auth.CHANGE_PASSWORD;
    }

    @PostMapping(Routes.Auth.LOGIN)
    public String login(
            @Valid @ModelAttribute LoginRequest request,
            BindingResult result,
            Model model,
            HttpServletRequest req,
            RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return Views.Auth.LOGIN;
        }
        try {
            UserResponse user = authService.login(request);
            req.getSession(true).setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    SecurityContextHolder.getContext());
            return switch (user.role()) {
                case "ADMIN" -> Routes.REDIRECT + Routes.Dashboard.ROOT + Routes.Role.ADMIN;
                case "MANAGER" -> Routes.REDIRECT + Routes.Dashboard.ROOT + Routes.Role.MANAGER;
                case "EMPLOYEE" -> Routes.REDIRECT + Routes.Dashboard.ROOT + Routes.Role.EMPLOYEE;
                default -> Routes.REDIRECT + Routes.Dashboard.ROOT;
            };
        } catch (EmailNotVerifiedException e) {
            // Không flash email thật ra ngoài: ai đó chỉ cần gõ đúng username (chưa cần biết
            // mật khẩu) không nên dò ra được email thật gắn với tài khoản đó. Giữ email ở
            // session server-side; verifyOtp()/resendPendingOtp() tự đọc lại từ đó.
            req.getSession(true).setAttribute(SESSION_PENDING_VERIFY_EMAIL, e.getEmail());
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Tài khoản chưa xác thực email. Vui lòng nhập mã OTP đã gửi tới email đã đăng ký.");
            redirectAttributes.addFlashAttribute("purpose", OtpPurpose.REGISTER.name());
            return Routes.REDIRECT + Routes.Auth.ROOT + Routes.Auth.VERIFY_OTP;
        } catch (DisabledException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return Views.Auth.LOGIN;
        } catch (AuthenticationException e) {
            model.addAttribute("errorMessage", "Tên đăng nhập hoặc mật khẩu không đúng.");
            return Views.Auth.LOGIN;
        }
    }

    @PostMapping(Routes.Auth.REGISTER)
    public String register(
            @Valid @ModelAttribute RegisterRequest request,
            BindingResult result,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return Views.Auth.REGISTER;
        }
        try {
            authService.register(request);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đăng ký thành công. Vui lòng nhập mã OTP đã gửi tới email để xác thực tài khoản.");
            redirectAttributes.addFlashAttribute("email", request.getEmail());
            redirectAttributes.addFlashAttribute("purpose", OtpPurpose.REGISTER.name());
            return Routes.REDIRECT + Routes.Auth.ROOT + Routes.Auth.VERIFY_OTP;
        } catch (IllegalArgumentException | ConflictException e) {
            request.setPassword("");
            request.setRePassword("");
            model.addAttribute("errorMessage", e.getMessage());
            return Views.Auth.REGISTER;
        }
    }

    @PostMapping(Routes.Auth.LOGOUT)
    public String logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        authService.logout();
        return Routes.REDIRECT + "/";
    }

    @PostMapping(Routes.Auth.FORGOT_PASSWORD)
    public String forgotPassword(
            @RequestParam("email") String email,
            RedirectAttributes redirectAttributes) {
        try {
            authService.forgotPassword(email);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Mã OTP đã được gửi đến email của bạn. Vui lòng kiểm tra email.");
            redirectAttributes.addFlashAttribute("email", email);
            return Routes.REDIRECT + Routes.Auth.ROOT + Routes.Auth.VERIFY_OTP;
        } catch (IllegalArgumentException | ResourceNotFoundException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return Routes.REDIRECT + Routes.Auth.ROOT + Routes.Auth.FORGOT_PASSWORD;
        }
    }

    @PostMapping(Routes.Auth.VERIFY_OTP)
    public String verifyOtp(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam("otp") String otpCode,
            @RequestParam(value = "purpose", required = false, defaultValue = "FORGOT_PASSWORD") String purpose,
            HttpServletRequest request,
            Model model,
            RedirectAttributes redirectAttributes) {
        OtpPurpose parsedPurpose = tryParsePurpose(purpose);
        OtpPurpose otpPurpose = parsedPurpose != null ? parsedPurpose : OtpPurpose.FORGOT_PASSWORD;

        HttpSession existingSession = request.getSession(false);
        boolean emailFromSession = false;
        if ((email == null || email.trim().isEmpty()) && existingSession != null) {
            Object pending = existingSession.getAttribute(SESSION_PENDING_VERIFY_EMAIL);
            if (pending != null) {
                email = pending.toString();
                emailFromSession = true;
            }
        }

        if (email == null || email.trim().isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Phiên làm việc không hợp lệ hoặc đã hết hạn. Vui lòng yêu cầu mã OTP mới.");
            return otpPurpose == OtpPurpose.REGISTER
                    ? Routes.REDIRECT + Routes.Auth.ROOT + Routes.Auth.REGISTER
                    : Routes.REDIRECT + Routes.Auth.ROOT + Routes.Auth.FORGOT_PASSWORD;
        }
        try {
            boolean isValid = authService.verifyOtp(email, otpCode, otpPurpose);
            if (!isValid) {
                model.addAttribute("errorMessage", "Mã OTP không chính xác hoặc đã hết hạn.");
                if (emailFromSession) {
                    model.addAttribute("emailPending", true);
                } else {
                    model.addAttribute("email", email);
                }
                model.addAttribute("purpose", purpose);
                return Views.Auth.VERIFY_OTP;
            }

            if (emailFromSession) {
                existingSession.removeAttribute(SESSION_PENDING_VERIFY_EMAIL);
            }

            if (otpPurpose == OtpPurpose.REGISTER) {
                redirectAttributes.addFlashAttribute("successMessage",
                        "Xác thực email thành công. Bạn có thể đăng nhập ngay bây giờ.");
                return Routes.REDIRECT + Routes.Auth.ROOT + Routes.Auth.LOGIN;
            }

            // Store OTP verified flag in session để dùng cho POST update-password
            HttpSession session = request.getSession();
            session.setAttribute("otpVerified", true);
            session.setAttribute("otpEmail", email);

            redirectAttributes.addFlashAttribute("email", email);
            redirectAttributes.addFlashAttribute("otpVerified", true);
            return Routes.REDIRECT + Routes.Auth.ROOT + Routes.Auth.CHANGE_PASSWORD + "?email=" + email;
        } catch (Exception e) {
            model.addAttribute("errorMessage", e.getMessage());
            if (emailFromSession) {
                model.addAttribute("emailPending", true);
            } else {
                model.addAttribute("email", email);
            }
            model.addAttribute("purpose", purpose);
            return Views.Auth.VERIFY_OTP;
        }
    }

    /**
     * Gửi lại OTP cho case "login phát hiện chưa xác thực" — email không có ở phía client
     * (xem {@link #login}), nên nút "Gửi lại mã" gọi endpoint này (không cần body) thay vì
     * {@code POST /api/otp/send} thông thường.
     */
    @PostMapping(Routes.Auth.VERIFY_OTP + "/resend-pending")
    public ResponseEntity<String> resendPendingOtp(final HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object pending = session != null ? session.getAttribute(SESSION_PENDING_VERIFY_EMAIL) : null;
        if (pending == null) {
            return ResponseEntity.badRequest().body("Phiên làm việc đã hết hạn, vui lòng đăng nhập lại.");
        }
        // AuthController là @Controller (trang MVC), không phải @RestController, nên
        // GlobalMvcExceptionHandler (trả về view name) mới là advice áp dụng cho lớp này —
        // tự bắt lỗi ở đây để trả JSON/text đúng như JS phía verify-otp.js đang mong đợi.
        try {
            otpService.send(SendOtpRequest.builder()
                    .destination(pending.toString())
                    .purpose(OtpPurpose.REGISTER)
                    .build());
            return ResponseEntity.noContent().build();
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getReason());
        }
    }

    /**
     * "Gửi lại mã" khi trang nhập OTP biết email (đăng ký / quên mật khẩu). Thay cho việc trình duyệt gọi
     * thẳng {@code POST /api/otp/send} (giờ chỉ mobile gateway gọi được, có X-API-Key). Luôn 204 dù email
     * có tài khoản hay không — xem AuthService.resendOtp.
     */
    @PostMapping(Routes.Auth.VERIFY_OTP + "/resend")
    public ResponseEntity<String> resendOtp(@RequestParam("email") final String email,
            @RequestParam("purpose") final String purpose) {
        OtpPurpose otpPurpose = tryParsePurpose(purpose);
        if (otpPurpose != OtpPurpose.REGISTER && otpPurpose != OtpPurpose.FORGOT_PASSWORD) {
            return ResponseEntity.badRequest().body("Yêu cầu không hợp lệ.");
        }
        try {
            authService.resendOtp(email.trim(), otpPurpose);
        } catch (ResponseStatusException e) {
            // Nuốt cả lỗi cooldown 429: chỉ email có tài khoản mới chạm cooldown, trả 429 sẽ lộ email nào tồn tại.
            // Nút "Gửi lại mã" đã tự đếm ngược phía trình duyệt (verify-otp.js) nên người dùng thật không mất gì.
        }
        return ResponseEntity.noContent().build();
    }

    private OtpPurpose tryParsePurpose(final String purpose) {
        try {
            return OtpPurpose.valueOf(purpose);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

}
