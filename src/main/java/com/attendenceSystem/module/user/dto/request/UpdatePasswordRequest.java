package com.attendenceSystem.module.user.dto.request;

import com.attendenceSystem.annotation.UpdatePasswordMatch;
import com.attendenceSystem.constant.PasswordPolicy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@UpdatePasswordMatch
public class UpdatePasswordRequest {
    @NotBlank(message = "Mật khẩu không được để trống")
    @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
    private String password;

    @NotBlank(message = "Mật khẩu xác nhận không được để trống")
    private String confirmPassword;
}
