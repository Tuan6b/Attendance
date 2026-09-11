package com.attendenceSystem.module.user.controller;

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
import com.attendenceSystem.module.otp.entity.enums.OtpPurpose;
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

@Controller
@RequestMapping(Routes.Auth.ROOT)
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

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
            Model model) {
        // email/purpose thường đến từ flash attribute (redirect từ register/forgotPassword/login);
        // chỉ dùng query param làm phương án dự phòng khi truy cập/tải lại trang trực tiếp.
        if (!model.containsAttribute("email") && email != null) {
            model.addAttribute("email", email);
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
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Tài khoản chưa xác thực email. Vui lòng nhập mã OTP đã gửi tới email của bạn.");
            redirectAttributes.addFlashAttribute("email", e.getEmail());
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
                model.addAttribute("email", email);
                model.addAttribute("purpose", purpose);
                return Views.Auth.VERIFY_OTP;
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
            model.addAttribute("email", email);
            model.addAttribute("purpose", purpose);
            return Views.Auth.VERIFY_OTP;
        }
    }

    private OtpPurpose tryParsePurpose(final String purpose) {
        try {
            return OtpPurpose.valueOf(purpose);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

}
