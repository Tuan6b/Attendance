package com.attendenceSystem.constant;

import java.util.regex.Pattern;

/**
 * Chính sách mật khẩu cho mọi chỗ đặt mật khẩu (đăng ký, đổi, quên mật khẩu): 8–72 ký tự, có ít nhất 1 chữ hoa,
 * 1 chữ thường, 1 chữ số và 1 ký tự đặc biệt (không phải chữ, số, khoảng trắng). Tối đa 72 vì BCrypt bỏ phần sau.
 * Gateway (mobileBackend) có bản sao cùng quy tắc cho luồng đặt lại mật khẩu máy quét.
 */
public final class PasswordPolicy {
    public static final String REGEX = "^(?=.*\\p{Ll})(?=.*\\p{Lu})(?=.*\\p{N})(?=.*[^\\p{L}\\p{N}\\s]).{8,72}$";
    public static final String MESSAGE =
            "Mật khẩu phải có 8–72 ký tự, gồm ít nhất 1 chữ hoa, 1 chữ thường, 1 chữ số và 1 ký tự đặc biệt";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        return password != null && PATTERN.matcher(password).matches();
    }
}
