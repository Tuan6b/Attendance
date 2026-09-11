package com.attendenceSystem.module.attendance.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import com.attendenceSystem.module.attendance.entity.enums.AttendanceCheckStatus;
import com.attendenceSystem.module.attendance.entity.enums.AttendanceStatus;
import com.attendenceSystem.module.attendance.entity.enums.WorkingStatus;

import lombok.Builder;

@Builder
public record ManagerAttendanceListResponse(
        Long id,
        String userFullName,
        LocalDate attendanceDate,
        LocalDateTime checkInTime,
        LocalDateTime checkOutTime,
        AttendanceStatus status,
        WorkingStatus workingStatus,
        Set<AttendanceCheckStatus> checkStatuses,
        String note,
        boolean late,
        boolean earlyLeave,
        long workingMinutes) {

}