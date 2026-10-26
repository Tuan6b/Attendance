package com.attendenceSystem.module.report.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.attendenceSystem.exception.custom.ResourceNotFoundException;
import com.attendenceSystem.module.report.dto.request.CreateReportRequest;
import com.attendenceSystem.module.report.dto.response.ReportDetailResponse;
import com.attendenceSystem.module.report.dto.response.ReportResponse;
import com.attendenceSystem.module.report.entity.Report;
import com.attendenceSystem.module.report.entity.ReportShare;
import com.attendenceSystem.module.report.mapper.request.CreateReportRequestMapper;
import com.attendenceSystem.module.report.mapper.response.ReportDetailResponseMapper;
import com.attendenceSystem.module.report.mapper.response.ReportResponseMapper;
import com.attendenceSystem.module.report.repository.ReportRepository;
import com.attendenceSystem.module.report.repository.ReportShareRepository;
import com.attendenceSystem.module.report.service.ReportService;
import com.attendenceSystem.module.storage.service.FileStorageService;
import com.attendenceSystem.module.user.entity.User;
import com.attendenceSystem.module.user.entity.enums.Role;
import com.attendenceSystem.module.user.provider.UserContextProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportServiceImpl implements ReportService {

    private final ReportRepository reportRepository;
    private final ReportShareRepository reportShareRepository;
    private final FileStorageService fileStorageService;
    private final UserContextProvider userContextProvider;

    @Value("${app.storage.upload-dir:uploads}")
    private String storageBaseDir;

    @Transactional
    @Override
    public ReportResponse createReport(final CreateReportRequest request) {
        User employee = userContextProvider.getCurrentUserEntity();

        Report report = CreateReportRequestMapper.toEntity(request, employee);

        // Xử lý upload files
        if (request.getFiles() != null && request.getFiles().length > 0) {
            List<String> filePaths = new ArrayList<>();
            for (MultipartFile file : request.getFiles()) {
                if (!file.isEmpty()) {
                    try {
                        String filePath = fileStorageService.saveFile(file, "reports");
                        filePaths.add(filePath);
                    } catch (IOException e) {
                        throw new RuntimeException("Không thể lưu file đính kèm: " + e.getMessage(), e);
                    }
                }
            }
            if (!filePaths.isEmpty()) {
                report.setAttachmentFiles(String.join(",", filePaths));
            }
        }

        Report savedReport = reportRepository.save(report);

        // Save shared users
        if (request.getSharedUserIds() != null && request.getSharedUserIds().length > 0) {
            List<ReportShare> shares = new ArrayList<>();
            for (Long userId : request.getSharedUserIds()) {
                User sharedUser = userContextProvider.getUserById(userId);
                shares.add(ReportShare.builder()
                        .report(savedReport)
                        .user(sharedUser)
                        .build());
            }
            reportShareRepository.saveAll(shares);
        }

        return ReportResponseMapper.fromEntity(savedReport);
    }

    // readOnly transaction: attachment_files là @Lob (oid trên Postgres), đọc ngoài transaction sẽ lỗi
    @Transactional(readOnly = true)
    @Override
    public Page<ReportResponse> getAllReports(final Pageable pageable) {
        return reportRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(ReportResponseMapper::fromEntity);
    }

    // readOnly transaction: attachment_files là @Lob (oid trên Postgres), đọc ngoài transaction sẽ lỗi
    @Transactional(readOnly = true)
    @Override
    public Page<ReportResponse> getMyReports(final Pageable pageable) {
        User employee = userContextProvider.getCurrentUserEntity();
        return reportRepository.findByEmployeeOrderByCreatedAtDesc(employee, pageable)
                .map(ReportResponseMapper::fromEntity);
    }

    // readOnly transaction: attachment_files là @Lob (oid trên Postgres), đọc ngoài transaction sẽ lỗi
    @Transactional(readOnly = true)
    @Override
    public Page<ReportResponse> getSharedWithMe(final Pageable pageable) {
        User currentUser = userContextProvider.getCurrentUserEntity();
        return reportShareRepository.findReportsSharedWithUser(currentUser, pageable)
                .map(ReportResponseMapper::fromEntity);
    }

    /** Không có quyền cũng trả "không tìm thấy" để không dò được id báo cáo nào tồn tại. */
    // readOnly transaction: attachment_files là @Lob (oid trên Postgres), đọc ngoài transaction sẽ lỗi
    @Transactional(readOnly = true)
    @Override
    public ReportDetailResponse getReportById(final Long id) {
        User viewer = userContextProvider.getCurrentUserEntity();
        Report report = reportRepository.findByIdWithShares(id)
                .filter(r -> canView(r, viewer))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy báo cáo với id: " + id));
        return ReportDetailResponseMapper.fromEntity(report);
    }

    /**
     * File đính kèm (vd "/uploads/reports/<sha256>.pdf") xem được nếu nằm trong một báo cáo người đang đăng nhập
     * xem được. readOnly transaction: đọc cột @Lob (oid) trên Postgres cần transaction.
     */
    @Transactional(readOnly = true)
    @Override
    public boolean canViewAttachment(final String fileUrl) {
        User viewer = userContextProvider.getCurrentUserEntity();
        return viewer.getRole() == Role.ADMIN || reportRepository.findVisibleTo(viewer.getId()).stream()
                .map(Report::getAttachmentFiles)
                .filter(Objects::nonNull)
                .anyMatch(files -> Arrays.asList(files.split(",")).contains(fileUrl));
    }

    /** Người nộp, người được chia sẻ, hoặc ADMIN — khớp với danh sách my-reports / shared-with-me. */
    static boolean canView(final Report report, final User viewer) {
        Long viewerId = viewer.getId();
        return viewer.getRole() == Role.ADMIN
                || report.getEmployee().getId().equals(viewerId)
                || report.getShares() != null && report.getShares().stream()
                        .anyMatch(share -> share.getUser().getId().equals(viewerId));
    }
}
