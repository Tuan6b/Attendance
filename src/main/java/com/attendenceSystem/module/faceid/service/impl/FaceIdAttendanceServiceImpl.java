package com.attendenceSystem.module.faceid.service.impl;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.attendenceSystem.module.attendance.dto.response.AttendanceResponse;
import com.attendenceSystem.module.attendance.entity.AttendanceRecord;
import com.attendenceSystem.module.attendance.entity.enums.LeaveStatus;
import com.attendenceSystem.module.attendance.exception.AlreadyCheckedInException;
import com.attendenceSystem.module.attendance.exception.AlreadyCheckedOutException;
import com.attendenceSystem.module.attendance.exception.InvalidAttendanceStateException;
import com.attendenceSystem.module.attendance.exception.NotCheckedInException;
import com.attendenceSystem.module.attendance.repository.AttendanceRecordRepository;
import com.attendenceSystem.module.attendance.repository.LeaveRequestRepository;
import com.attendenceSystem.module.attendance.service.AttendanceActionService;
import com.attendenceSystem.module.attendance.service.AttendanceService;
import com.attendenceSystem.module.faceid.dto.request.FaceIdAttendanceRequest;
import com.attendenceSystem.module.faceid.dto.response.FaceIdAttendanceResponse;
import com.attendenceSystem.module.faceid.entity.FaceProfile;
import com.attendenceSystem.module.faceid.entity.FaceSample;
import com.attendenceSystem.module.faceid.entity.enums.FaceIdAction;
import com.attendenceSystem.module.faceid.exception.FaceAiException;
import com.attendenceSystem.module.faceid.exception.FaceAiUnavailableException;
import com.attendenceSystem.module.faceid.repository.FaceProfileRepository;
import com.attendenceSystem.module.faceid.repository.FaceSampleRepository;
import com.attendenceSystem.module.faceid.service.FaceAiClient;
import com.attendenceSystem.module.faceid.service.FaceIdAttendanceService;
import com.attendenceSystem.module.faceid.service.FaceIdLogService;
import com.attendenceSystem.module.user.entity.User;
import com.attendenceSystem.module.user.entity.enums.Status;
import com.attendenceSystem.module.user.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Chấm công bằng khuôn mặt — chỉ mobile gateway gọi (X-API-Key, xem FaceIdApiKeyFilter).
 * Attendance tự kiểm tra lại ảnh (khớp mặt + người thật) qua face-ai, không tin kết quả client gửi.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FaceIdAttendanceServiceImpl implements FaceIdAttendanceService {

    private static final String LIVE = "live";

    private final AttendanceService attendanceService;
    private final AttendanceActionService attendanceActionService;
    private final UserRepository userRepository;
    private final FaceIdLogService faceIdLogService;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final FaceProfileRepository faceProfileRepository;
    private final FaceSampleRepository faceSampleRepository;
    private final FaceAiClient faceAiClient;

    @Value("${face-id.confidence-threshold:0.55}")
    private double confidenceThreshold;

    private final Cache<String, FaceIdAction> processedTrackingIds = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(100_00)
            .build();

    @Transactional
    @Override
    public FaceIdAttendanceResponse processAttendance(FaceIdAttendanceRequest request) {
        String trackingId = request.getTrackingId();
        LocalDateTime timestamp = LocalDateTime.now();

        // 1. Check idempotency (only if trackingId is present)
        if (StringUtils.hasText(trackingId)) {
            FaceIdAction cachedAction = processedTrackingIds.getIfPresent(trackingId);
            if (cachedAction != null) {
                return buildResponseAndLog(request, FaceIdAction.IGNORED, "DUPLICATE", trackingId, timestamp, null);
            }
        }

        // 2. Find user by username (studentCode)
        User user = userRepository.findByUsername(request.getStudentCode())
                .orElse(null);

        if (user == null) {
            String message = "User not found: " + request.getStudentCode();
            log.warn(message);
            return buildResponseAndLog(request, FaceIdAction.FAILED, message, trackingId, timestamp, null);
        }
        // Gateway cache khuôn mặt 5 phút → user vừa bị khoá vẫn có thể được nhận ra; chặn ở đây.
        if (user.getStatus() != Status.ACTIVE) {
            String message = "Tài khoản nhân viên không hoạt động.";
            log.warn("Từ chối điểm danh khuôn mặt cho tài khoản không ACTIVE: {}", request.getStudentCode());
            return buildResponseAndLog(request, FaceIdAction.FAILED, message, trackingId, timestamp, null);
        }

        // 3. Verify the captured image against the user face samples using face-ai
        if (!StringUtils.hasText(request.getImageBase64())) {
            String message = "Thiếu ảnh chụp để nhận diện khuôn mặt.";
            log.warn(message);
            return buildResponseAndLog(request, FaceIdAction.FAILED, message, trackingId, timestamp, null);
        }

        FaceProfile faceProfile = faceProfileRepository.findByUser(user).orElse(null);
        if (faceProfile == null) {
            String message = "Chưa có dữ liệu khuôn mặt đã đăng ký cho người dùng này.";
            log.warn(message);
            return buildResponseAndLog(request, FaceIdAction.FAILED, message, trackingId, timestamp, null);
        }

        List<FaceAiClient.KnownFace> knownFaces = faceSampleRepository.findByFaceProfileOrderBySampleOrderAsc(faceProfile)
                .stream()
                .filter(sample -> StringUtils.hasText(sample.getEmbedding()))
                .map(sample -> toKnownFace(user, sample))
                .toList();
        if (knownFaces.isEmpty()) {
            String message = "Không tìm thấy mẫu khuôn mặt nào đã đăng ký.";
            log.warn(message);
            return buildResponseAndLog(request, FaceIdAction.FAILED, message, trackingId, timestamp, null);
        }

        // Một lần gọi /recognize: khớp mặt với mẫu của chính user này + kiểm tra người thật + chất lượng ảnh
        FaceAiClient.RecognizeResponse recognition;
        try {
            recognition = faceAiClient.recognize(request.getImageBase64(), knownFaces, confidenceThreshold);
        } catch (FaceAiUnavailableException e) {
            String message = "Hệ thống AI nhận diện hiện không khả dụng. Vui lòng thử lại sau.";
            log.warn("Nhận diện điểm danh thất bại do AI không khả dụng: {}", e.getMessage());
            return buildResponseAndLog(request, FaceIdAction.FAILED, message, trackingId, timestamp, null);
        } catch (FaceAiException e) {
            // Chi tiết lỗi từ face-ai (URL nội bộ, body) chỉ ghi log, không trả cho client
            String message = "Lỗi xử lý ảnh từ hệ thống AI nhận diện.";
            log.warn("Nhận diện điểm danh thất bại: {}", e.getMessage());
            return buildResponseAndLog(request, FaceIdAction.FAILED, message, trackingId, timestamp, null);
        }

        String rejection = rejectionReason(recognition);
        if (rejection != null) {
            log.warn("Từ chối điểm danh khuôn mặt cho {}: {}", request.getStudentCode(), rejection);
            return buildResponseAndLog(request, FaceIdAction.FAILED, rejection, trackingId, timestamp, null);
        }

        // 4. Determine action using AttendanceService state query
        FaceIdAction action;
        AttendanceResponse attendanceResponse;
        String responseMessage;

        try {
            AttendanceDecision decision = decideCheckInOrOut(user, request.getImageBase64());
            action = decision.action();
            attendanceResponse = decision.attendance();
            responseMessage = decision.message();

            appendLeaveNoteIfOnLeave(user, action);

            log.info("Face ID attendance processed: student={}, action={}", request.getStudentCode(), action);

        } catch (AlreadyCheckedInException | AlreadyCheckedOutException | InvalidAttendanceStateException
                | NotCheckedInException e) {
            // Đây là các quy tắc nghiệp vụ bình thường (vd quá giờ ca, đã điểm danh đủ 2 lượt...),
            // không phải lỗi hệ thống — log warn để tránh làm nhiễu log với stack trace không
            // cần thiết mỗi khi một nhân viên đến muộn.
            String errorMessage = e.getMessage();
            log.warn("Điểm danh bị từ chối theo quy tắc nghiệp vụ cho {}: {}", request.getStudentCode(), errorMessage);
            return buildResponseAndLog(request, FaceIdAction.FAILED, errorMessage, trackingId, timestamp, null);
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            log.error("Attendance processing error for student {}: {}", request.getStudentCode(), errorMessage, e);
            return buildResponseAndLog(request, FaceIdAction.FAILED, errorMessage, trackingId, timestamp, null);
        }

        // 5. Cache trackingId for idempotency
        if (StringUtils.hasText(trackingId)) {
            processedTrackingIds.put(trackingId, action);
        }

        // 6. Build and return response
        return buildResponseAndLog(request, action, responseMessage, trackingId, timestamp, attendanceResponse);
    }

    private static FaceAiClient.KnownFace toKnownFace(User user, FaceSample sample) {
        return new FaceAiClient.KnownFace(sample.getId(), user.getUsername(), user.getFullName(), sample.getEmbedding());
    }

    /**
     * null = ảnh hợp lệ để chấm công; ngược lại là lý do từ chối (tiếng Việt, trả cho máy quét).
     * Fail closed: kiểm tra người thật bị tắt hoặc model chưa nạp thì face-ai vẫn báo "live" —
     * ở đây coi là từ chối.
     */
    static String rejectionReason(FaceAiClient.RecognizeResponse recognition) {
        if (recognition == null || !recognition.success) {
            return recognition != null && StringUtils.hasText(recognition.message)
                    ? recognition.message
                    : "Không xử lý được ảnh chụp.";
        }
        if (recognition.results == null || recognition.results.size() != 1) {
            return "Ảnh phải có đúng 1 khuôn mặt.";
        }
        FaceAiClient.RecognizedFace face = recognition.results.get(0);
        if (face.liveness == null
                || !Boolean.TRUE.equals(face.liveness.get("enabled"))
                || !Boolean.TRUE.equals(face.liveness.get("model_loaded"))) {
            return "Hệ thống kiểm tra người thật chưa sẵn sàng, không thể chấm công.";
        }
        if (!LIVE.equals(face.liveness_status)) {
            return StringUtils.hasText(face.note) ? face.note : "Không vượt qua kiểm tra người thật.";
        }
        if (!face.recognized) {
            return "Không khớp khuôn mặt với dữ liệu đăng ký.";
        }
        return null;
    }

    private record AttendanceDecision(FaceIdAction action, String message, AttendanceResponse attendance) {
    }

    private AttendanceDecision decideCheckInOrOut(User user, String imageBase64) {
        Optional<AttendanceRecord> todayRecord = attendanceService.getTodayAttendanceRecord(user);

        if (todayRecord.isEmpty()) {
            AttendanceResponse response = attendanceActionService.checkIn(user, imageBase64);
            return new AttendanceDecision(FaceIdAction.CHECKIN, "Checked in successfully", response);
        }
        if (todayRecord.get().getCheckOutTime() == null) {
            AttendanceResponse response = attendanceActionService.checkOut(user, imageBase64);
            return new AttendanceDecision(FaceIdAction.CHECKOUT, "Checked out successfully", response);
        }
        return new AttendanceDecision(FaceIdAction.IGNORED, "Already checked out today", null);
    }

    /**
     * Nếu user đang trong ngày nghỉ phép đã duyệt, ghi chú vào attendance record. Cố tình tự
     * bắt lỗi và chỉ log warn — một checkin/checkout đã thành công không nên bị báo FAILED chỉ
     * vì bước ghi chú phụ này lỗi.
     */
    private void appendLeaveNoteIfOnLeave(User user, FaceIdAction action) {
        if (action != FaceIdAction.CHECKIN && action != FaceIdAction.CHECKOUT) {
            return;
        }

        try {
            LocalDate today = LocalDate.now();
            List<Long> onLeaveUserIds = leaveRequestRepository.findUserIdsOnLeaveForDate(today, LeaveStatus.APPROVED);
            if (!onLeaveUserIds.contains(user.getId())) {
                return;
            }

            Optional<AttendanceRecord> updatedRecord = attendanceRecordRepository.findByUserAndAttendanceDate(user, today);
            updatedRecord.ifPresent(record -> {
                String currentNote = record.getNote() != null ? record.getNote() + "; " : "";
                record.setNote(currentNote + "Đi làm trong ngày nghỉ phép");
                attendanceRecordRepository.save(record);
            });
        } catch (RuntimeException e) {
            log.warn("Không thể ghi chú ngày nghỉ cho user {}", user.getUsername(), e);
        }
    }

    private FaceIdAttendanceResponse buildResponseAndLog(
            FaceIdAttendanceRequest request,
            FaceIdAction action,
            String message,
            String trackingId,
            LocalDateTime timestamp,
            AttendanceResponse attendance) {

        faceIdLogService.saveRecognitionLog(request, action, message, attendance, timestamp);

        return FaceIdAttendanceResponse.builder()
                .success(action != FaceIdAction.FAILED)
                .action(action)
                .message(message)
                .trackingId(trackingId)
                .timestamp(timestamp)
                .attendance(attendance)
                .build();
    }
}
