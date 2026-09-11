package com.attendenceSystem.module.user.exception;

import lombok.Getter;

/**
 * Ném ra khi ai đó đăng nhập bằng tài khoản chưa xác thực OTP email (xem
 * {@code User.emailVerified}). Xác thực OTP là điều kiện duy nhất để một tài khoản tự đăng ký
 * được kích hoạt (ACTIVE) — không còn bước admin duyệt thủ công riêng nữa.
 */
@Getter
public class EmailNotVerifiedException extends RuntimeException {
    private final String email;

    public EmailNotVerifiedException(final String email) {
        super("Email chưa được xác thực. Vui lòng xác thực OTP trước khi đăng nhập.");
        this.email = email;
    }
}
