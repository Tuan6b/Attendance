package com.attendenceSystem.module.faceid.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.attendenceSystem.constant.Routes;
import com.attendenceSystem.module.faceid.dto.request.FaceCaptureRequest;
import com.attendenceSystem.module.faceid.dto.request.FaceIdAttendanceRequest;
import com.attendenceSystem.module.faceid.dto.request.FaceIdManualAttendanceRequest;
import com.attendenceSystem.module.faceid.dto.request.FaceIdentifyRequest;
import com.attendenceSystem.module.faceid.dto.response.EmployeeDirectoryResponse;
import com.attendenceSystem.module.faceid.dto.response.FaceCaptureResponse;
import com.attendenceSystem.module.faceid.dto.response.FaceIdAttendanceResponse;
import com.attendenceSystem.module.faceid.dto.response.FaceIdentifyResponse;
import com.attendenceSystem.module.faceid.dto.response.LatestFaceResponse;
import com.attendenceSystem.module.faceid.exception.FaceAiException;
import com.attendenceSystem.module.faceid.exception.FaceAiUnavailableException;
import com.attendenceSystem.module.faceid.service.FaceAiClient;
import com.attendenceSystem.module.faceid.service.FaceIdAttendanceService;
import com.attendenceSystem.module.faceid.service.FaceIdCaptureService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping(Routes.API + Routes.FaceId.ROOT)
@RequiredArgsConstructor
@Slf4j
public class FaceIdApiController {
    private final FaceIdCaptureService faceIdCaptureService;
    private final FaceIdAttendanceService faceIdAttendanceService;

    @PostMapping("/register")
    public ResponseEntity<FaceCaptureResponse> registerFace(@RequestBody FaceCaptureRequest request) {
        try {
            FaceCaptureResponse response = faceIdCaptureService.registerFace(request);
            return response.success() ? ResponseEntity.ok(response) : ResponseEntity.badRequest().body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(FaceCaptureResponse.builder().message(e.getMessage()).success(false).build());
        } catch (FaceAiUnavailableException e) {
            log.warn("Đăng ký khuôn mặt thất bại do Face AI không khả dụng: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(FaceCaptureResponse.builder().message("Hệ thống AI nhận diện hiện không khả dụng. Vui lòng thử lại sau.").success(false).build());
        } catch (FaceAiException e) {
            log.warn("Đăng ký khuôn mặt thất bại do lỗi Face AI: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(FaceCaptureResponse.builder().message("Lỗi xử lý từ hệ thống AI: " + e.getMessage()).success(false).build());
        } catch (Exception e) {
            log.error("Lỗi khi đăng ký face", e);
            return ResponseEntity.internalServerError()
                    .body(FaceCaptureResponse.builder().message("Lỗi máy chủ").success(false).build());
        }
    }

    public static class PoseCheckRequest {
        public String image_base64;
    }

    @PostMapping("/pose-check")
    public ResponseEntity<FaceAiClient.PoseResult> poseCheck(@RequestBody PoseCheckRequest request) {
        return ResponseEntity.ok(faceIdCaptureService.checkPose(request.image_base64));
    }

    @GetMapping("/latest")
    public ResponseEntity<LatestFaceResponse> getLatestFace() {
        try {
            return ResponseEntity.ok(faceIdCaptureService.getLatestFace());
        } catch (IllegalStateException e) {
            // Endpoint này permitAll (để trang kiosk /attendance/check dùng được ẩn danh), nên
            // "chưa đăng nhập" là tình huống bình thường với khách kiosk — không phải lỗi máy
            // chủ. Trả 401 đúng ngữ nghĩa thay vì rơi vào catch-all 500 bên dưới; JS phía
            // client (attendance-check.js) đã tự xử lý nhánh lỗi này rồi.
            return ResponseEntity.status(401).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Lỗi khi lấy ảnh mới nhất", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/update-for-attendance")
    public ResponseEntity<FaceCaptureResponse> updateForAttendance(@RequestBody FaceCaptureRequest request) {
        try {
            return ResponseEntity.ok(faceIdCaptureService.updateForAttendance(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(FaceCaptureResponse.builder().message(e.getMessage()).success(false).build());
        } catch (FaceAiUnavailableException e) {
            log.warn("Cập nhật ảnh điểm danh thất bại do Face AI không khả dụng: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(FaceCaptureResponse.builder().message("Hệ thống AI nhận diện hiện không khả dụng. Vui lòng thử lại sau.").success(false).build());
        } catch (FaceAiException e) {
            log.warn("Cập nhật ảnh điểm danh thất bại do lỗi Face AI: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(FaceCaptureResponse.builder().message("Lỗi xử lý từ hệ thống AI: " + e.getMessage()).success(false).build());
        } catch (Exception e) {
            log.error("Lỗi khi cập nhật ảnh điểm danh", e);
            return ResponseEntity.internalServerError()
                    .body(FaceCaptureResponse.builder().message("Lỗi máy chủ").success(false).build());
        }
    }

    @DeleteMapping("/sample/{sampleId}")
    public ResponseEntity<Void> deleteFaceSample(@PathVariable Long sampleId) {
        try {
            faceIdCaptureService.deleteFaceSample(sampleId);
            return ResponseEntity.noContent().build();
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Lỗi khi xóa file", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/attendance")
    public ResponseEntity<FaceIdAttendanceResponse> processFaceIdAttendance(
            @RequestBody FaceIdAttendanceRequest request) {

        FaceIdAttendanceResponse response = faceIdAttendanceService.processAttendance(request);
        return response.success() ? ResponseEntity.ok(response) : ResponseEntity.badRequest().body(response);
    }

    @PostMapping("/identify")
    public ResponseEntity<FaceIdentifyResponse> identify(@RequestBody FaceIdentifyRequest request) {
        return ResponseEntity.ok(faceIdAttendanceService.identify(request.getImageBase64()));
    }

    @GetMapping("/employees")
    public ResponseEntity<List<EmployeeDirectoryResponse>> listEmployees() {
        return ResponseEntity.ok(faceIdAttendanceService.listEmployeesForManualAttendance());
    }

    
    @PostMapping("/attendance/manual")
    public ResponseEntity<FaceIdAttendanceResponse> processManualAttendance(
            @RequestBody FaceIdManualAttendanceRequest request) {

        FaceIdAttendanceResponse response = faceIdAttendanceService.processManualAttendance(request);
        return response.success() ? ResponseEntity.ok(response) : ResponseEntity.badRequest().body(response);
    }
}