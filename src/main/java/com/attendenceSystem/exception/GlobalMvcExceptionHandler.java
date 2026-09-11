package com.attendenceSystem.exception;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

import com.attendenceSystem.exception.custom.BadRequestException;
import com.attendenceSystem.exception.custom.ConflictException;
import com.attendenceSystem.exception.custom.ResourceNotFoundException;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Trang lỗi cho các Controller Thymeleaf (không phải API) chưa có xử lý riêng.
 *
 * <p>Cố tình liệt kê từng package thay vì dùng {@code annotations = Controller.class}: vì
 * {@code @RestController} cũng được đánh dấu meta bằng {@code @Controller}, nếu dùng
 * {@code annotations} thì advice này sẽ vô tình áp cho cả các {@code *ApiController} và trả
 * về tên view thay vì JSON/text — phá luồng gọi API bằng {@code fetch}. Không liệt kê
 * {@code module.attendance.controller} vì module đó đã có
 * {@link com.attendenceSystem.module.attendance.exception.AttendanceControllerExceptionHandler}
 * riêng.
 */
@Slf4j
@ControllerAdvice(basePackages = {
        "com.attendenceSystem.core",
        "com.attendenceSystem.module.user.controller",
        "com.attendenceSystem.module.faceid.controller",
        "com.attendenceSystem.module.report.controller",
        "com.attendenceSystem.module.schedule.controller",
        "com.attendenceSystem.module.system.controller",
        "com.attendenceSystem.module.dashboard.controller"
})
public class GlobalMvcExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound() {
        return "error/404t";
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String forbidden() {
        return "error/403t";
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String conflict() {
        return "error/400t";
    }

    @ExceptionHandler({BadRequestException.class, IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest() {
        return "error/400t";
    }

    @ExceptionHandler(ResponseStatusException.class)
    public String responseStatus(final ResponseStatusException ex, final HttpServletResponse response) {
        response.setStatus(ex.getStatusCode().value());
        return switch (ex.getStatusCode().value()) {
            case 404 -> "error/404t";
            case 403 -> "error/403t";
            case 400 -> "error/400t";
            default -> "error/500t";
        };
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String internalServerError(final Exception ex) {
        log.error("Unhandled MVC exception", ex);
        return "error/500t";
    }
}
