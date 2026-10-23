package com.attendenceSystem.module.attendance.controller;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.attendenceSystem.constant.Routes;
import com.attendenceSystem.constant.Views;
import com.attendenceSystem.module.attendance.dto.request.CreateLeaveRequest;
import com.attendenceSystem.module.attendance.dto.response.AttendanceHistoryStatsResponse;
import com.attendenceSystem.module.attendance.dto.response.EmployeeAttendanceListResponse;
import com.attendenceSystem.module.attendance.dto.response.EmployeeLeaveListResponse;
import com.attendenceSystem.module.attendance.dto.response.ManagerLeaveListResponse;
import com.attendenceSystem.module.attendance.entity.enums.AttendanceStatus;
import com.attendenceSystem.module.attendance.entity.enums.LeaveStatus;
import com.attendenceSystem.module.attendance.service.AttendanceService;
import com.attendenceSystem.module.attendance.service.LeaveService;

import lombok.RequiredArgsConstructor;

/**
 * Chỉ còn render trang (GET). Các hành động ghi dữ liệu (check-in/out, tạo/duyệt/từ chối đơn
 * nghỉ phép) đã chuyển sang gọi API JSON bằng JS — xem {@code AttendanceApiController},
 * {@code AttendanceManagerApiController} và {@code static/js/absent/absent-create.js},
 * {@code templates/cms/absent/absent-list.html}. {@code checkIn}/{@code checkOut} MVC cũ đã
 * xoá vì không còn view/JS nào gọi tới (kiosk dùng {@code /api/face-id/attendance}).
 */
@Controller
@RequiredArgsConstructor
@RequestMapping(Routes.Attendance.ROOT)
public class AttendanceController {
    private final AttendanceService attendanceService;
    private final LeaveService leaveService;

    @GetMapping
    public String toAttendanceListPage(
            @PageableDefault(size = 10, sort = "attendanceDate", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {
        model.addAttribute("attendanceHistory", attendanceService.getAttendanceHistory(pageable));
        return Views.Attendance.LIST;
    }

    @GetMapping(Routes.Attendance.HISTORY)
    public String attendanceHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) AttendanceStatus status,
            @PageableDefault(size = 10, sort = "attendanceDate", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {

        boolean hasFilter = startDate != null || endDate != null || status != null;

        Page<EmployeeAttendanceListResponse> attendanceHistory;
        AttendanceHistoryStatsResponse stats;

        if (hasFilter) {
            attendanceHistory = attendanceService.getAttendanceHistory(startDate, endDate, status, pageable);
            stats = attendanceService.getAttendanceHistoryStats(startDate, endDate, status);
        } else {
            attendanceHistory = attendanceService.getAttendanceHistory(pageable);
            stats = attendanceService.getAttendanceHistoryStats();
        }

        model.addAttribute("attendanceHistory", attendanceHistory);
        model.addAttribute("stats", stats);
        model.addAttribute("selectedStartDate", startDate);
        model.addAttribute("selectedEndDate", endDate);
        model.addAttribute("selectedStatus", status == null ? null : status.name());
        model.addAttribute("hasFilter", hasFilter);

        return Views.Attendance.HISTORY;
    }

    @GetMapping(Routes.Attendance.LEAVE)
    public String leaveRequestList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) String week,
            @PageableDefault(size = 10) Pageable pageable,
            Model model) {

        boolean hasFilter = (keyword != null && !keyword.isBlank()) || status != null || (week != null && !week.isBlank());

        Page<ManagerLeaveListResponse> leaveRequests;
        if (hasFilter) {
            leaveRequests = leaveService.getAllLeaveRequests(keyword, status, week, pageable);
        } else {
            leaveRequests = leaveService.getAllLeaveRequests(pageable);
        }

        model.addAttribute("leaveRequests", leaveRequests);
        model.addAttribute("selectedKeyword", keyword);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedWeek", week);
        model.addAttribute("hasFilter", hasFilter);

        return Views.Attendance.LEAVE_LIST;
    }

    @GetMapping(Routes.Attendance.MY_LEAVE)
    public String myLeaveRequestList(
            @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) String week,
            @PageableDefault(size = 10) Pageable pageable,
            Model model) {

        boolean hasFilter = status != null || (week != null && !week.isBlank());

        Page<EmployeeLeaveListResponse> leaveRequests;
        if (hasFilter) {
            leaveRequests = leaveService.getLeaveRequests(status, week, pageable);
        } else {
            leaveRequests = leaveService.getLeaveRequests(pageable);
        }

        model.addAttribute("leaveRequests", leaveRequests);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedWeek", week);
        model.addAttribute("hasFilter", hasFilter);

        return Views.Attendance.MY_LEAVE_LIST;
    }

    @GetMapping(Routes.Attendance.LEAVE + Routes.Action.CREATE)
    public String toLeaveRequestPage(Model model) {
        model.addAttribute("createLeaveRequest", new CreateLeaveRequest());
        return Views.Attendance.LEAVE_CREATE;
    }
}
