package com.attendenceSystem.module.attendance.entity.enums;

import java.time.LocalDateTime;

/**
 * Trạng thái làm việc theo thời gian thực của một bản ghi điểm danh, phái sinh từ
 * checkInTime/checkOutTime (không lưu xuống DB). Khác với {@link AttendanceStatus}
 * (PRESENT/ABSENT/LEAVE/DAY_OFF) vốn mô tả loại ngày công.
 */
public enum WorkingStatus {
    /** Đang làm việc: đã check-in, chưa check-out. */
    WORKING,
    /** Không làm việc: đã check-out (hết ca). */
    CHECKED_OUT,
    /** Nghỉ: chưa check-in trong ngày. */
    NOT_CHECKED_IN;

    public static WorkingStatus fromCheckTimes(final LocalDateTime checkInTime, final LocalDateTime checkOutTime) {
        if (checkInTime == null) {
            return NOT_CHECKED_IN;
        }
        return checkOutTime == null ? WORKING : CHECKED_OUT;
    }
}
