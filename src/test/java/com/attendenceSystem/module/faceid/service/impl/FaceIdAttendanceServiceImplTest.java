package com.attendenceSystem.module.faceid.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.attendenceSystem.module.attendance.repository.AttendanceRecordRepository;
import com.attendenceSystem.module.attendance.repository.LeaveRequestRepository;
import com.attendenceSystem.module.attendance.service.AttendanceActionService;
import com.attendenceSystem.module.attendance.service.AttendanceService;
import com.attendenceSystem.module.faceid.dto.request.FaceIdAttendanceRequest;
import com.attendenceSystem.module.faceid.dto.response.FaceIdAttendanceResponse;
import com.attendenceSystem.module.faceid.entity.FaceProfile;
import com.attendenceSystem.module.faceid.entity.FaceSample;
import com.attendenceSystem.module.faceid.entity.enums.FaceIdAction;
import com.attendenceSystem.module.faceid.repository.FaceProfileRepository;
import com.attendenceSystem.module.faceid.repository.FaceSampleRepository;
import com.attendenceSystem.module.faceid.service.FaceAiClient;
import com.attendenceSystem.module.faceid.service.FaceIdLogService;
import com.attendenceSystem.module.user.entity.User;
import com.attendenceSystem.module.user.entity.enums.Status;
import com.attendenceSystem.module.user.repository.UserRepository;

class FaceIdAttendanceServiceImplTest {

    // ---- rejectionReason: chỉ chấp nhận đúng 1 mặt, người thật (kiểm tra bật + model đã nạp), khớp mẫu ----

    @Test
    void acceptsSingleLiveRecognizedFace() {
        assertNull(FaceIdAttendanceServiceImpl.rejectionReason(response(face(true, "live", true, true))));
    }

    @Test
    void rejectsSpoofedFace() {
        assertEquals("ảnh giả", FaceIdAttendanceServiceImpl.rejectionReason(response(face(true, "fake", true, true))));
    }

    @Test
    void rejectsUncertainLiveness() {
        assertTrue(FaceIdAttendanceServiceImpl.rejectionReason(response(face(true, "uncertain", true, true))) != null);
    }

    @Test
    void rejectsWhenLivenessDisabledEvenIfFaceAiSaysLive() {
        // face-ai trả "live" khi kiểm tra người thật bị tắt → phải từ chối (fail closed)
        assertTrue(FaceIdAttendanceServiceImpl.rejectionReason(response(face(true, "live", false, true))) != null);
    }

    @Test
    void rejectsWhenLivenessModelNotLoaded() {
        assertTrue(FaceIdAttendanceServiceImpl.rejectionReason(response(face(true, "live", true, false))) != null);
    }

    @Test
    void rejectsUnrecognizedFace() {
        assertEquals("Không khớp khuôn mặt với dữ liệu đăng ký.",
                FaceIdAttendanceServiceImpl.rejectionReason(response(face(false, "live", true, true))));
    }

    @Test
    void rejectsMoreThanOneFace() {
        FaceAiClient.RecognizeResponse two = response(face(true, "live", true, true));
        two.results = List.of(two.results.get(0), face(true, "live", true, true));
        assertEquals("Ảnh phải có đúng 1 khuôn mặt.", FaceIdAttendanceServiceImpl.rejectionReason(two));
    }

    @Test
    void rejectsFailedRecognition() {
        FaceAiClient.RecognizeResponse failed = new FaceAiClient.RecognizeResponse();
        failed.success = false;
        failed.message = "Không thấy mặt";
        assertEquals("Không thấy mặt", FaceIdAttendanceServiceImpl.rejectionReason(failed));
    }

    // ---- processAttendance: chỉ ghi công khi face-ai xác nhận người thật ----

    @Test
    void processAttendanceChecksInOnlyAfterLiveMatch() {
        Fixture f = new Fixture(response(face(true, "live", true, true)));

        FaceIdAttendanceResponse response = f.service.processAttendance(request());

        assertTrue(response.success());
        assertEquals(FaceIdAction.CHECKIN, response.action());
        verify(f.actionService).checkIn(any(), anyString());
    }

    @Test
    void processAttendanceRejectsPhotoAttack() {
        Fixture f = new Fixture(response(face(true, "fake", true, true)));

        FaceIdAttendanceResponse response = f.service.processAttendance(request());

        assertFalse(response.success());
        assertEquals(FaceIdAction.FAILED, response.action());
        verify(f.actionService, never()).checkIn(any(), anyString());
    }

    @Test
    void processAttendanceRejectsInactiveEmployee() {
        Fixture f = new Fixture(response(face(true, "live", true, true)), Status.INACTIVE);

        FaceIdAttendanceResponse response = f.service.processAttendance(request());

        assertFalse(response.success());
        verify(f.actionService, never()).checkIn(any(), anyString());
    }

    /** Service với user "nv01" có 1 mẫu khuôn mặt; face-ai /recognize trả [recognition]. */
    private static final class Fixture {
        final AttendanceActionService actionService = mock(AttendanceActionService.class);
        final FaceIdAttendanceServiceImpl service;

        Fixture(FaceAiClient.RecognizeResponse recognition) {
            this(recognition, Status.ACTIVE);
        }

        Fixture(FaceAiClient.RecognizeResponse recognition, Status status) {
            User user = new User();
            user.setUsername("nv01");
            user.setStatus(status);
            FaceProfile profile = new FaceProfile();
            FaceSample sample = new FaceSample();
            sample.setId(1L);
            sample.setEmbedding("AAAAAA==");

            UserRepository users = mock(UserRepository.class);
            when(users.findByUsername("nv01")).thenReturn(Optional.of(user));
            FaceProfileRepository profiles = mock(FaceProfileRepository.class);
            when(profiles.findByUser(user)).thenReturn(Optional.of(profile));
            FaceSampleRepository samples = mock(FaceSampleRepository.class);
            when(samples.findByFaceProfileOrderBySampleOrderAsc(profile)).thenReturn(List.of(sample));
            FaceAiClient faceAi = mock(FaceAiClient.class);
            when(faceAi.recognize(anyString(), anyList(), anyDouble())).thenReturn(recognition);
            AttendanceService attendance = mock(AttendanceService.class);
            when(attendance.getTodayAttendanceRecord(user)).thenReturn(Optional.empty());
            LeaveRequestRepository leaves = mock(LeaveRequestRepository.class);
            when(leaves.findUserIdsOnLeaveForDate(any(), any())).thenReturn(List.of());

            service = new FaceIdAttendanceServiceImpl(attendance, actionService, users, mock(FaceIdLogService.class),
                    leaves, mock(AttendanceRecordRepository.class), profiles, samples, faceAi);
        }
    }

    private static FaceIdAttendanceRequest request() {
        FaceIdAttendanceRequest request = new FaceIdAttendanceRequest();
        request.setStudentCode("nv01");
        request.setConfidence(0.9);
        request.setCapturedAt(LocalDateTime.now());
        request.setImageBase64("data:image/jpeg;base64,AAAA");
        return request;
    }

    private static FaceAiClient.RecognizeResponse response(FaceAiClient.RecognizedFace face) {
        FaceAiClient.RecognizeResponse response = new FaceAiClient.RecognizeResponse();
        response.success = true;
        response.results = List.of(face);
        return response;
    }

    private static FaceAiClient.RecognizedFace face(boolean recognized, String livenessStatus,
            boolean livenessEnabled, boolean modelLoaded) {
        FaceAiClient.RecognizedFace face = new FaceAiClient.RecognizedFace();
        face.recognized = recognized;
        face.confidence = 0.9;
        face.liveness_status = livenessStatus;
        face.note = "fake".equals(livenessStatus) ? "ảnh giả" : null;
        Map<String, Object> liveness = new HashMap<>();
        liveness.put("enabled", livenessEnabled);
        liveness.put("model_loaded", modelLoaded);
        face.liveness = liveness;
        return face;
    }
}
