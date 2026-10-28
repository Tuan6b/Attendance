package com.attendenceSystem.constant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.attendenceSystem.exception.custom.BadRequestException;
import com.attendenceSystem.module.user.dto.request.UpdatePasswordRequest;
import com.attendenceSystem.module.user.entity.User;
import com.attendenceSystem.module.user.provider.UserContextProvider;
import com.attendenceSystem.module.user.repository.UserRepository;
import com.attendenceSystem.module.user.service.impl.UserServiceImpl;

class PasswordPolicyTest {

    @Test
    void acceptsPasswordsMeetingAllRules() {
        assertTrue(PasswordPolicy.isValid("NewPass-123"));
        assertTrue(PasswordPolicy.isValid("Ab1!Ab1!"));
        assertTrue(PasswordPolicy.isValid("Mật-Khẩu9"));     // chữ có dấu tính là chữ, không phải ký tự đặc biệt
    }

    @Test
    void rejectsPasswordsMissingAnyRule() {
        assertFalse(PasswordPolicy.isValid(null));
        assertFalse(PasswordPolicy.isValid("Ab1!Ab1"));      // 7 ký tự
        assertFalse(PasswordPolicy.isValid("newpass-123"));  // thiếu chữ hoa
        assertFalse(PasswordPolicy.isValid("NEWPASS-123"));  // thiếu chữ thường
        assertFalse(PasswordPolicy.isValid("NewPass-abc"));  // thiếu số
        assertFalse(PasswordPolicy.isValid("NewPass123"));   // thiếu ký tự đặc biệt
        assertFalse(PasswordPolicy.isValid("NewPass 123"));  // khoảng trắng không tính là ký tự đặc biệt
        assertFalse(PasswordPolicy.isValid("Mật khẩu9a"));   // "ậ", "ẩ" là chữ, không phải ký tự đặc biệt
        assertFalse(PasswordPolicy.isValid("Aa1!" + "x".repeat(69))); // 73 ký tự, quá giới hạn BCrypt
    }

    /** Form đổi mật khẩu (UserController) không qua @Valid → service phải tự chặn. */
    @Test
    void updatePasswordRejectsWeakPasswordWithoutValidation() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UserRepository repository = mock(UserRepository.class);
        var service = new UserServiceImpl(encoder, repository, null, mock(UserContextProvider.class));

        assertThrows(BadRequestException.class,
                () -> service.updatePassword(new User(), new UpdatePasswordRequest("12345678", "12345678")));
        verifyNoInteractions(encoder, repository);
    }
}
