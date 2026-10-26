package com.attendenceSystem.module.report.service.impl;

import static com.attendenceSystem.module.report.service.impl.ReportServiceImpl.canView;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.attendenceSystem.module.report.entity.Report;
import com.attendenceSystem.module.report.entity.ReportShare;
import com.attendenceSystem.module.user.entity.User;
import com.attendenceSystem.module.user.entity.enums.Role;

class ReportServiceImplTest {

    private final User owner = user(1L, Role.EMPLOYEE);
    private final User sharedManager = user(2L, Role.MANAGER);
    private final Report report = Report.builder()
            .employee(owner)
            .shares(List.of(ReportShare.builder().user(sharedManager).build()))
            .build();

    @Test
    void ownerAndSharedUsersCanView() {
        assertTrue(canView(report, owner));
        assertTrue(canView(report, sharedManager));
    }

    @Test
    void otherUsersCannotViewEvenManagers() {
        assertFalse(canView(report, user(3L, Role.EMPLOYEE)));
        assertFalse(canView(report, user(4L, Role.MANAGER)));
    }

    @Test
    void adminCanView() {
        assertTrue(canView(report, user(5L, Role.ADMIN)));
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
