package com.attendenceSystem.module.storage.controller;

import static com.attendenceSystem.module.storage.controller.UploadController.canRead;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.attendenceSystem.module.user.entity.enums.Role;

class UploadControllerTest {

    @Test
    void employeeReadsOnlyOwnFaceAndEvidenceImages() {
        assertTrue(canRead("face_samples/7/face_7_1.jpg", 7L, Role.EMPLOYEE));
        assertTrue(canRead("attendance_evidence/7/checkin_x.jpg", 7L, Role.EMPLOYEE));
        assertFalse(canRead("face_samples/8/face_8_1.jpg", 7L, Role.EMPLOYEE));
        assertFalse(canRead("attendance_evidence/8/checkin_x.jpg", 7L, Role.EMPLOYEE));
        // không để "7" khớp tiền tố của "70"
        assertFalse(canRead("face_samples/70/face_70_1.jpg", 7L, Role.EMPLOYEE));
    }

    @Test
    void managersAndAdminsReadAnyEmployeeImage() {
        assertTrue(canRead("face_samples/8/face_8_1.jpg", 1L, Role.MANAGER));
        assertTrue(canRead("attendance_evidence/8/checkout_x.jpg", 1L, Role.ADMIN));
    }

    @Test
    void reportAttachmentsNeedOnlyLogin() {
        assertTrue(canRead("reports/abc.pdf", 7L, Role.EMPLOYEE));
    }

    @Test
    void unknownDirectoriesAndTraversalAreDenied() {
        assertFalse(canRead("secrets/x.jpg", 1L, Role.ADMIN));
        assertFalse(canRead("face_samples/x.jpg", 1L, Role.ADMIN));
        assertFalse(canRead("face_samples/7/../8/face_8_1.jpg", 7L, Role.EMPLOYEE));
        assertFalse(canRead("reports/../face_samples/8/face_8_1.jpg", 7L, Role.EMPLOYEE));
        assertFalse(canRead("face_samples//7/a.jpg", 7L, Role.EMPLOYEE));
    }
}
