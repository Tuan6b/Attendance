package com.attendenceSystem.module.user.service.impl;

import com.attendenceSystem.constant.PasswordPolicy;
import com.attendenceSystem.exception.custom.ConflictException;
import com.attendenceSystem.exception.custom.ResourceNotFoundException;
import com.attendenceSystem.module.user.mapper.request.RegisterRequestMapper;
import com.attendenceSystem.module.user.mapper.response.UserResponseMapper;
import java.util.Optional;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.attendenceSystem.module.otp.dto.request.SendOtpRequest;
import com.attendenceSystem.module.otp.dto.request.VerifyOtpRequest;
import com.attendenceSystem.module.otp.entity.enums.OtpPurpose;
import com.attendenceSystem.module.otp.service.OtpService;
import com.attendenceSystem.module.user.dto.request.LoginRequest;
import com.attendenceSystem.module.user.dto.request.RegisterRequest;
import com.attendenceSystem.module.user.dto.response.UserResponse;
import com.attendenceSystem.module.user.entity.User;
import com.attendenceSystem.module.user.entity.enums.Status;
import com.attendenceSystem.module.user.exception.EmailNotVerifiedException;
import com.attendenceSystem.module.user.repository.UserRepository;
import com.attendenceSystem.module.user.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserResponseMapper userResponseMapper;
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;

    @Transactional
    @Override
    public void register(final RegisterRequest request) {
        if (existsByKeyword(request.getUsername())) {
            throw new ConflictException("Tên đăng nhập đã tồn tại!");
        }
        if (existsByKeyword(request.getEmail())) {
            throw new ConflictException("Email này đã được đăng ký!");
        }
        if (!PasswordPolicy.isValid(request.getPassword())) {
            throw new IllegalArgumentException(PasswordPolicy.MESSAGE);
        }
        String hashPassword = passwordEncoder.encode(request.getPassword());
        User savedUser = RegisterRequestMapper.toEntity(request, hashPassword);
        userRepository.save(savedUser);

        otpService.send(SendOtpRequest.builder()
                .destination(savedUser.getEmail())
                .purpose(OtpPurpose.REGISTER)
                .build());
    }

    @Override
    public void logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                HttpServletResponse response = attributes.getResponse();
                new SecurityContextLogoutHandler().logout(request, response, auth);
            }
        }
    }

    @Transactional
    @Override
    public UserResponse login(final LoginRequest request) {
        // Xác thực email (OTP lúc đăng ký) là điều kiện độc lập, kiểm tra trước cả khi thử xác
        // thực mật khẩu — tài khoản chưa xác thực luôn bị chặn dù mật khẩu đúng hay sai, và
        // dù trạng thái duyệt (status) là gì.
        findUser(request.getLogin())
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> {
                    throw new EmailNotVerifiedException(user.getEmail());
                });

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getLogin(),
                            request.getPassword()));
        } catch (DisabledException e) {
            User user = findUser(request.getLogin())
                    .orElseThrow(() -> new DisabledException("Tên đăng nhập hoặc mật khẩu không đúng.", e));
            if (user.getStatus() == Status.PENDING) {
                throw new DisabledException("Tài khoản đang chờ duyệt", e);
            }
            throw new DisabledException("Tài khoản đã bị khóa", e);
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        User user = findUser(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tên đăng nhập hoặc mật khẩu không đúng."));
        return userResponseMapper.fromEntity(user);
    }

    @Override
    public void forgotPassword(final String email) {
        User user = findUser(email)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với email: " + email));

        SendOtpRequest request = SendOtpRequest.builder()
                .destination(user.getEmail())
                .purpose(OtpPurpose.FORGOT_PASSWORD)
                .build();

        otpService.send(request);
    }

    @Transactional
    @Override
    public boolean verifyOtp(final String destination, final String code, final OtpPurpose purpose) {
        try {
            VerifyOtpRequest request = VerifyOtpRequest.builder()
                    .destination(destination)
                    .code(code)
                    .purpose(purpose)
                    .build();

            otpService.verify(request);

            if (purpose == OtpPurpose.REGISTER) {
                // Xác thực OTP là điều kiện duy nhất để kích hoạt tài khoản tự đăng ký — bỏ
                // bước admin phải bấm "mở khóa" thủ công (nút đó vốn dùng chung cho cả việc
                // mở khóa tài khoản bị khóa, không phải một quy trình duyệt riêng — xem trao
                // đổi trước). Admin vẫn khoá/mở khoá được bình thường sau này nếu cần.
                findUser(destination).ifPresent(user -> {
                    user.setEmailVerified(true);
                    if (user.getStatus() == Status.PENDING) {
                        user.setStatus(Status.ACTIVE);
                    }
                    userRepository.save(user);
                });
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Nút "Gửi lại mã" trên trang nhập OTP. Chỉ gửi tới email của một tài khoản đang ở đúng bước
     * (quên mật khẩu: tài khoản tồn tại; đăng ký: tài khoản chờ xác thực email); trường hợp khác im lặng
     * bỏ qua để không dò được email nào có tài khoản. Lỗi cooldown 429 của OtpService vẫn ném ra.
     */
    @Override
    public void resendOtp(final String email, final OtpPurpose purpose) {
        Optional<User> user = userRepository.findByEmail(email);
        boolean eligible = switch (purpose) {
            case FORGOT_PASSWORD -> user.isPresent();
            case REGISTER -> user.filter(u -> u.getStatus() == Status.PENDING && !u.isEmailVerified()).isPresent();
            default -> false;
        };
        if (eligible) {
            otpService.send(SendOtpRequest.builder().destination(user.get().getEmail()).purpose(purpose).build());
        }
    }

    private Optional<User> findUser(final String keyword) {
        return userRepository.findByUsernameOrEmail(keyword, keyword);
    }

    private boolean existsByKeyword(final String keyword) {
        return userRepository.existsByUsernameOrEmail(keyword, keyword);
    }
}
