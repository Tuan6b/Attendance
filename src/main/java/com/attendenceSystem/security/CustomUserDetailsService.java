package com.attendenceSystem.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import com.attendenceSystem.module.user.entity.User;
import com.attendenceSystem.module.user.entity.enums.Role;
import com.attendenceSystem.module.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

        private final UserRepository userRepository;

        @Override
        public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {

                User user = userRepository
                                .findByUsernameOrEmail(login, login)
                                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy User"));
                // Tài khoản máy quét chỉ dùng cho mobile gateway; báo như sai tài khoản (không lộ role)
                if (user.getRole() == Role.CHECKER) {
                        throw new UsernameNotFoundException("Không tìm thấy User");
                }

                return CustomUserDetails.fromUser(user);
        }
}