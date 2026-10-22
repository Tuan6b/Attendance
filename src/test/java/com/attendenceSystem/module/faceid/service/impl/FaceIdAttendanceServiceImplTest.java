package com.attendenceSystem.module.faceid.service.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.attendenceSystem.module.faceid.dto.request.FaceIdManualAttendanceRequest;
import com.attendenceSystem.module.faceid.dto.response.FaceIdAttendanceResponse;
import com.attendenceSystem.module.faceid.entity.enums.FaceIdAction;
import com.attendenceSystem.module.user.entity.User;
import com.attendenceSystem.module.user.entity.enums.Status;
import com.attendenceSystem.security.CustomUserDetails;

import com.attendenceSystem.module.attendance.repository.AttendanceRecordRepository;
import com.attendenceSystem.module.attendance.repository.LeaveRequestRepository;
import com.attendenceSystem.module.attendance.service.AttendanceActionService;
import com.attendenceSystem.module.attendance.service.AttendanceService;
import com.attendenceSystem.module.faceid.dto.response.FaceIdentifyResponse;
import com.attendenceSystem.module.faceid.repository.FaceProfileRepository;
import com.attendenceSystem.module.faceid.repository.FaceSampleRepository;
import com.attendenceSystem.module.faceid.service.FaceAiClient;
import com.attendenceSystem.module.faceid.service.FaceEmbeddingCacheService;
import com.attendenceSystem.module.faceid.service.FaceIdLogService;
import com.attendenceSystem.module.user.repository.UserRepository;

class FaceIdAttendanceServiceImplTest {

    @org.junit.jupiter.api.Test
    void shouldRecognizeSameEmbeddingWithHighSimilarity() {
        createService();
        String embedding = encodeEmbedding(new float[]{1f, 0f, 0f});

        double similarity = FaceIdAttendanceServiceImpl.cosineSimilarity(embedding, embedding);

        assertTrue(similarity >= 0.99d);
    }

    @org.junit.jupiter.api.Test
    void shouldRejectDifferentEmbeddingBelowThreshold() {
        createService();
        String embeddingA = encodeEmbedding(new float[]{1f, 0f, 0f});
        String embeddingB = encodeEmbedding(new float[]{0f, 1f, 0f});

        double similarity = FaceIdAttendanceServiceImpl.cosineSimilarity(embeddingA, embeddingB);

        assertTrue(similarity < 0.1d);
    }

    @org.junit.jupiter.api.Test
    void identifyWhenImageMissingReturnsMissingImageMessage() {
        FaceIdAttendanceServiceImpl service = createService();

        FaceIdentifyResponse response = service.identify(null);

        assertEquals(false, response.isFaceDetected());
        assertEquals(false, response.isMatched());
        assertEquals("Thiếu ảnh", response.getMessage());
    }

    @org.junit.jupiter.api.Test
    void identifyWhenAiUnavailableThrowsFaceAiUnavailableException() {
        FaceAiClient faceAiClient = mock(FaceAiClient.class);
        org.mockito.Mockito.when(faceAiClient.checkPose(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new com.attendenceSystem.module.faceid.exception.FaceAiUnavailableException("Connection refused"));

        FaceIdAttendanceServiceImpl service = createService(faceAiClient);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.attendenceSystem.module.faceid.exception.FaceAiUnavailableException.class,
                () -> service.identify("some-image")
        );
    }

    @org.junit.jupiter.api.Test
    void manualAttendanceRejectsInactiveEmployeeWithoutCheckingIn() {
        UserRepository userRepository = mock(UserRepository.class);
        AttendanceActionService actionService = mock(AttendanceActionService.class);
        User locked = new User();
        locked.setUsername("nv01");
        locked.setStatus(Status.INACTIVE);
        when(userRepository.findByUsername("nv01")).thenReturn(Optional.of(locked));
        FaceIdAttendanceServiceImpl service = new FaceIdAttendanceServiceImpl(
                mock(AttendanceService.class), actionService, userRepository, mock(FaceIdLogService.class),
                mock(LeaveRequestRepository.class), mock(AttendanceRecordRepository.class),
                mock(FaceProfileRepository.class), mock(FaceSampleRepository.class),
                mock(FaceAiClient.class), mock(FaceEmbeddingCacheService.class));

        FaceIdAttendanceResponse response = service.processManualAttendance(manualRequest("nv01"));

        assertFalse(response.success());
        assertEquals(FaceIdAction.FAILED, response.action());
        verifyNoInteractions(actionService);
    }

    @org.junit.jupiter.api.Test
    void manualAttendanceLogRecordsLoggedInManager() {
        UserRepository userRepository = mock(UserRepository.class);
        FaceIdLogService logService = mock(FaceIdLogService.class);
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        FaceIdAttendanceServiceImpl service = new FaceIdAttendanceServiceImpl(
                mock(AttendanceService.class), mock(AttendanceActionService.class), userRepository, logService,
                mock(LeaveRequestRepository.class), mock(AttendanceRecordRepository.class),
                mock(FaceProfileRepository.class), mock(FaceSampleRepository.class),
                mock(FaceAiClient.class), mock(FaceEmbeddingCacheService.class));
        CustomUserDetails manager = mock(CustomUserDetails.class);
        when(manager.getId()).thenReturn(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(manager, null, List.of()));
        try {
            service.processManualAttendance(manualRequest("ghost"));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(logService).saveManualRecognitionLog(any(), eq(FaceIdAction.FAILED), any(), any(), any(), eq(7L));
    }

    private static FaceIdManualAttendanceRequest manualRequest(String employeeCode) {
        FaceIdManualAttendanceRequest request = new FaceIdManualAttendanceRequest();
        request.setEmployeeCode(employeeCode);
        request.setCapturedAt(LocalDateTime.now());
        return request;
    }

    private static FaceIdAttendanceServiceImpl createService() {
        return createService(mock(FaceAiClient.class));
    }

    private static FaceIdAttendanceServiceImpl createService(FaceAiClient faceAiClient) {
        return new FaceIdAttendanceServiceImpl(
                mock(AttendanceService.class),
                mock(AttendanceActionService.class),
                mock(UserRepository.class),
                mock(FaceIdLogService.class),
                mock(LeaveRequestRepository.class),
                mock(AttendanceRecordRepository.class),
                mock(FaceProfileRepository.class),
                mock(FaceSampleRepository.class),
                faceAiClient,
                mock(FaceEmbeddingCacheService.class)
        );
    }

    private static String encodeEmbedding(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }
}
