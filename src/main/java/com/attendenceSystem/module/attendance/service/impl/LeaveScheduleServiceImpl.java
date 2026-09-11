package com.attendenceSystem.module.attendance.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.attendenceSystem.config.SystemConfig;
import com.attendenceSystem.exception.custom.BadRequestException;
import com.attendenceSystem.module.attendance.entity.LeaveRequest;
import com.attendenceSystem.module.attendance.entity.enums.LeaveStatus;
import com.attendenceSystem.module.attendance.repository.LeaveRequestRepository;
import com.attendenceSystem.module.attendance.service.LeaveScheduleService;
import com.attendenceSystem.module.system.util.StringConvert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveScheduleServiceImpl implements LeaveScheduleService {
    private final LeaveRequestRepository leaveRequestRepository;
    private final SystemConfig systemConfig;
    private final ZoneId applicationZoneId;

    private static final LocalTime DEFAULT_AUTO_REJECT_TIME = LocalTime.of(22, 0);
    private static final int DEFAULT_BLACKOUT_MINUTES = 2;

    @Override
    public void validateBlackoutPeriod(final LocalDate startDate) {
        LocalDateTime now = LocalDateTime.now(applicationZoneId);
        LocalTime currentTime = now.toLocalTime();
        LocalDate tomorrow = now.toLocalDate().plusDays(1);

        LocalTime autoReject = resolveAutoRejectTime();

        int blackoutMinutes = systemConfig.get().getLeaveBlackoutMinutes() != null
                ? systemConfig.get().getLeaveBlackoutMinutes()
                : DEFAULT_BLACKOUT_MINUTES;
        LocalTime blackoutStart = autoReject.minusMinutes(blackoutMinutes);
        LocalTime blackoutEnd = autoReject.plusMinutes(blackoutMinutes);

        boolean isInBlackoutPeriod = currentTime.isAfter(blackoutStart) && currentTime.isBefore(blackoutEnd);
        boolean isAfterRejectTime = currentTime.isAfter(autoReject);
        boolean isBeforeMidnight = currentTime.isBefore(LocalTime.of(23, 59));

        if (isInBlackoutPeriod) {
            throw new BadRequestException(
                    "Không thể tạo đơn xin nghỉ trong khoảng thời gian " + blackoutStart + " - " + blackoutEnd);
        }

        if (isAfterRejectTime && isBeforeMidnight && startDate.equals(tomorrow)) {
            throw new BadRequestException(
                    "Không thể tạo đơn xin nghỉ cho ngày mai sau thời gian " + autoReject);
        }
    }

    /**
     * Đọc giờ tự động từ chối thực tế từ {@code system_settings.leave_auto_reject_cron}
     * (dạng "sec min hour * * *", xem {@link com.attendenceSystem.config.SystemScheduler})
     * thay vì hardcode 22:00 — nếu không thì thời gian chặn gửi đơn sẽ lệch khỏi thời điểm
     * job auto-reject thực sự chạy mỗi khi admin đổi cron ở trang /system.
     */
    private LocalTime resolveAutoRejectTime() {
        String cron = systemConfig.get().getLeaveAutoRejectCron();
        if (cron == null || cron.isBlank()) {
            return DEFAULT_AUTO_REJECT_TIME;
        }
        try {
            return StringConvert.fromCron(cron);
        } catch (RuntimeException e) {
            log.warn("Không parse được leaveAutoRejectCron='{}', dùng mặc định {}", cron, DEFAULT_AUTO_REJECT_TIME, e);
            return DEFAULT_AUTO_REJECT_TIME;
        }
    }

    @Transactional
    public void autoRejectExpiredLeaveRequests() {
        LocalDate tomorrow = LocalDate.now(applicationZoneId).plusDays(1);
        log.info("Running auto-reject task for leave requests with startDate <= {}", tomorrow);

        var expiredLeaves = leaveRequestRepository.findByStatusAndStartDateLessThanEqual(LeaveStatus.PENDING, tomorrow);

        if (expiredLeaves.isEmpty()) {
            log.info("No expired leave requests found");
            return;
        }

        log.info("Found {} expired leave requests to reject", expiredLeaves.size());

        for (LeaveRequest leave : expiredLeaves) {
            leave.setStatus(LeaveStatus.REJECTED);
            leaveRequestRepository.save(leave);
            log.info("Auto-rejected leave request ID: {} for user: {}, startDate: {}",
                    leave.getId(), leave.getUser().getId(), leave.getStartDate());
        }

        log.info("Auto-reject task completed successfully");
    }
}
