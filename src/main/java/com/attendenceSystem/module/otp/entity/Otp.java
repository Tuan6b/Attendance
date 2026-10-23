package com.attendenceSystem.module.otp.entity;

import java.time.LocalDateTime;

import com.attendenceSystem.module.otp.entity.enums.OtpPurpose;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "otp")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class Otp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @Column(name = "destination", nullable = false)
    private String destination;
    @Column(name = "code", nullable = false)
    private String code;
    @Column(name = "purpose", nullable = false)
    private OtpPurpose purpose;
    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "used", nullable = false)
    private boolean used;
    /** Số lần đã nhập mã (kể cả lần đúng) — chặn dò mã, xem OtpServiceImpl.verify. */
    @Builder.Default
    @Column(name = "attempts", nullable = false, columnDefinition = "integer default 0")
    private int attempts = 0;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiredAt);
    }
}
