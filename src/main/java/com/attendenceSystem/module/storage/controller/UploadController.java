package com.attendenceSystem.module.storage.controller;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

import com.attendenceSystem.constant.FileConstants;
import com.attendenceSystem.module.report.service.ReportService;
import com.attendenceSystem.module.storage.provider.StorageProvider;
import com.attendenceSystem.module.user.entity.enums.Role;
import com.attendenceSystem.util.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * Phục vụ file trong thư mục upload (thay cho resource handler public cũ). URL giữ nguyên
 * {@code /uploads/...} nên DB và giao diện không phải đổi. SecurityConfig bắt đăng nhập; ở đây
 * kiểm tra thêm quyền theo loại file:
 * <ul>
 * <li>{@code face_samples/{userId}/...}, {@code attendance_evidence/{userId}/...}: chủ ảnh, MANAGER, ADMIN</li>
 * <li>{@code reports/<sha256>.<ext>}: người nộp hoặc người được chia sẻ một báo cáo đính kèm file đó, hoặc ADMIN
 * (cùng quy tắc với ReportServiceImpl.canView)</li>
 * <li>còn lại: 404</li>
 * </ul>
 * Không có quyền cũng trả 404 để không lộ file nào tồn tại.
 */
@Controller
@RequiredArgsConstructor
public class UploadController {

    private static final String PREFIX = "/uploads/";
    private static final Set<String> PER_USER_DIRECTORIES = Set.of("face_samples", "attendance_evidence");
    private static final String REPORTS_DIRECTORY = "reports";
    /** FileStorageServiceImpl đặt tên file báo cáo = SHA-256 nội dung. */
    private static final Pattern REPORT_FILE = Pattern.compile("[0-9a-f]{64}\\.[a-z0-9]{2,5}");

    private final StorageProvider storageProvider;
    private final ReportService reportService;

    @GetMapping(PREFIX + "**")
    public ResponseEntity<Resource> serve(HttpServletRequest request) {
        String path = request.getServletPath().substring(PREFIX.length());
        String extension = StringUtils.getFilenameExtension(path);
        String mime = extension == null ? null : FileConstants.ALLOWED_EXTENSION_MIME.get(extension.toLowerCase());
        Long userId = SecurityUtil.getCurrentUserId();
        Role role = SecurityUtil.getCurrentUserRole();
        if (mime == null || !canRead(path, userId, role)) {
            return ResponseEntity.notFound().build();
        }
        if (path.startsWith(REPORTS_DIRECTORY + "/") && !reportService.canViewAttachment(PREFIX + path)) {
            return ResponseEntity.notFound().build();
        }

        Resource file;
        try {
            file = storageProvider.load(path);
        } catch (IOException | SecurityException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                // Ảnh sinh trắc/bằng chứng: không cho proxy/cache dùng chung lưu lại
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, mime.startsWith("image/") ? "inline" : "attachment")
                .body(file);
    }

    /** path: đường dẫn tương đối trong thư mục upload, vd "face_samples/12/face_12_1.jpg". */
    static boolean canRead(String path, Long currentUserId, Role role) {
        List<String> segments = List.of(path.split("/"));
        if (segments.size() < 2 || segments.stream().anyMatch(s -> s.isEmpty() || s.equals("..") || s.equals("."))) {
            return false;
        }
        String directory = segments.get(0);
        if (REPORTS_DIRECTORY.equals(directory)) {
            // Chỉ kiểm tra tên file; quyền theo báo cáo kiểm tra trong serve() (cần DB)
            return segments.size() == 2 && REPORT_FILE.matcher(segments.get(1)).matches();
        }
        if (!PER_USER_DIRECTORIES.contains(directory) || segments.size() < 3) {
            return false;
        }
        return role == Role.ADMIN || role == Role.MANAGER || segments.get(1).equals(String.valueOf(currentUserId));
    }
}
