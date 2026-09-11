package com.attendenceSystem.module.attendance.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Xử lý các exception đặc thù của module attendance (chỉ áp cho package
 * {@code module.attendance.api}). Chỉ khai báo những exception mà advice này biết — nếu một
 * exception không khớp handler nào ở đây (vd {@code ResourceNotFoundException},
 * {@code ConflictException}, lỗi 500 bất kỳ), Spring sẽ tự rơi xuống
 * {@link com.attendenceSystem.exception.GlobalExceptionHandler} (áp cho mọi
 * {@code @RestController}) — nên KHÔNG khai báo handler {@code Exception.class} ở đây, tránh
 * nuốt nhầm những lỗi lẽ ra phải trả 404/409/403 thành 500.
 */
@RestControllerAdvice(basePackages = "com.attendenceSystem.module.attendance.api")
public class AttendanceExceptionHandler {

    @ExceptionHandler({
        AlreadyCheckedInException.class,
        AlreadyCheckedOutException.class,
        NotCheckedInException.class,
        InvalidAttendanceStateException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<String> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
