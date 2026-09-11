package com.attendenceSystem.module.attendance.exception;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.attendenceSystem.constant.Routes;

import lombok.extern.slf4j.Slf4j;

/**
 * Tách ra từ {@code AttendanceController} (trước đây 2 hàm {@code @ExceptionHandler} nằm
 * ngay trong controller). Luôn trả về redirect (không render view trực tiếp) vì
 * {@code RedirectAttributes.addFlashAttribute} chỉ thực sự hoạt động qua một redirect thật —
 * trả view name trực tiếp (như code cũ) khiến flash message không bao giờ hiển thị.
 */
@Slf4j
@ControllerAdvice(basePackages = "com.attendenceSystem.module.attendance.controller")
public class AttendanceControllerExceptionHandler {

    @ExceptionHandler({
            AlreadyCheckedInException.class,
            AlreadyCheckedOutException.class,
            NotCheckedInException.class,
            InvalidAttendanceStateException.class,
            IllegalArgumentException.class,
            IllegalStateException.class
    })
    public String handleBadRequest(final RuntimeException ex, final RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        return Routes.REDIRECT + Routes.Attendance.ROOT;
    }

    @ExceptionHandler(Exception.class)
    public String handleUnexpectedError(final Exception ex, final RedirectAttributes redirectAttributes) {
        log.error("Unexpected error in Attendance controller", ex);
        redirectAttributes.addFlashAttribute("errorMessage", "Có lỗi xảy ra, vui lòng thử lại sau");
        return Routes.REDIRECT + Routes.Attendance.ROOT;
    }
}
