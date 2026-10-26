package com.attendenceSystem.module.user.entity.enums;

import java.util.EnumSet;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {
    ADMIN(1), MANAGER(2), EMPLOYEE(3),
    /** Tài khoản máy quét khuôn mặt — chỉ đăng nhập mobile gateway, không vào web (CustomUserDetailsService). */
    CHECKER(4);

    /** Không phải nhân sự: loại khỏi danh sách nhân viên, thống kê, danh sách chia sẻ báo cáo. */
    public static final Set<Role> NOT_STAFF = EnumSet.of(ADMIN, CHECKER);

    public final int value;
    public static Role fromValue(int value){
        for(Role role : Role.values()){
            if(role.value == value){
                return role;
            }
        }
        throw new IllegalArgumentException("Không tìm thấy vai trò: "+ value);
    }
}
