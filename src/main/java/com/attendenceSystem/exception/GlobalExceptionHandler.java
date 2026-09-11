package com.attendenceSystem.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.attendenceSystem.exception.custom.BadRequestException;
import com.attendenceSystem.exception.custom.ConflictException;
import com.attendenceSystem.exception.custom.ResourceNotFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * Xử lý lỗi tập trung cho toàn bộ tầng API (mọi lớp {@code @RestController}, bất kể nằm ở
 * package nào) để trả đúng mã HTTP thay vì luôn rơi vào 500 mặc định của Spring Boot.
 *
 * <p>Chỉ áp dụng cho {@code @RestController} ({@code annotations = RestController.class}) nên
 * KHÔNG ảnh hưởng tới các trang Thymeleaf ({@code @Controller}) — xem
 * {@link GlobalMvcExceptionHandler} cho tầng đó. Trả về body dạng text thuần (không phải JSON)
 * để khớp với {@code static/js/api/method.js} hiện tại, vốn đọc lỗi bằng
 * {@code response.text()} rồi hiển thị thẳng qua {@code error.message}.
 *
 * <p>Một module có thể tự khai báo advice riêng, phạm vi hẹp hơn (vd
 * {@link com.attendenceSystem.module.attendance.exception.AttendanceExceptionHandler}) và đặt
 * {@code @Order} thấp hơn (ưu tiên cao hơn) để advice đó thắng handler chung này.
 */
@Slf4j
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> handleNotFound(final ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<String> handleConflict(final ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleForbidden(final AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
    }

    /**
     * Một số service (vd {@code AttendanceServiceImpl}) tự ném
     * {@link ResponseStatusException} với status/reason riêng — tôn trọng đúng status đó thay
     * vì để rơi vào handler {@code Exception.class} bên dưới (sẽ biến 404 thành 500).
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatus(final ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ex.getReason());
    }

    @ExceptionHandler({
            BadRequestException.class,
            IllegalArgumentException.class,
            IllegalStateException.class
    })
    public ResponseEntity<String> handleBadRequest(final RuntimeException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(final Exception ex) {
        log.error("Unhandled API exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Có lỗi xảy ra, vui lòng thử lại sau");
    }
}
