package com.attendenceSystem.module.otp.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.attendenceSystem.module.otp.dto.request.VerifyOtpRequest;
import com.attendenceSystem.module.otp.entity.Otp;
import com.attendenceSystem.module.otp.entity.enums.OtpPurpose;
import com.attendenceSystem.module.otp.exception.OtpInvalidException;
import com.attendenceSystem.module.otp.repository.OtpRepository;
import com.attendenceSystem.module.otp.service.OtpSender;

class OtpServiceImplTest {

    private static final String EMAIL = "nv@example.com";

    private final OtpRepository repository = mock(OtpRepository.class);
    private final OtpServiceImpl service = new OtpServiceImpl(repository, mock(OtpSender.class));
    private final Otp otp = Otp.builder()
            .id(1L).destination(EMAIL).code("123456").purpose(OtpPurpose.FORGOT_PASSWORD)
            .createdAt(LocalDateTime.now()).expiredAt(LocalDateTime.now().plusMinutes(5))
            .build();

    OtpServiceImplTest() {
        when(repository.findTopByDestinationAndPurposeAndUsedFalseOrderByCreatedAtDesc(EMAIL, OtpPurpose.FORGOT_PASSWORD))
                .thenReturn(Optional.of(otp));
    }

    @Test
    void correctCodeWithinAttemptLimitIsAccepted() {
        when(repository.registerAttempt(eq(1L), anyInt())).thenReturn(1);

        service.verify(request("123456"));

        assertTrue(otp.isUsed());
    }

    @Test
    void wrongCodeIsRejectedAndCountedBeforeComparing() {
        when(repository.registerAttempt(eq(1L), anyInt())).thenReturn(1);

        assertThrows(OtpInvalidException.class, () -> service.verify(request("000000")));
        verify(repository).registerAttempt(eq(1L), anyInt());
        assertFalse(otp.isUsed());
    }

    @Test
    void evenCorrectCodeIsRejectedOnceAttemptsAreExhausted() {
        // Dò hết lượt rồi mới đoán trúng vẫn không được
        when(repository.registerAttempt(eq(1L), anyInt())).thenReturn(0);

        assertThrows(OtpInvalidException.class, () -> service.verify(request("123456")));
        assertFalse(otp.isUsed());
        verify(repository, never()).save(otp);
    }

    private static VerifyOtpRequest request(String code) {
        return VerifyOtpRequest.builder().destination(EMAIL).code(code).purpose(OtpPurpose.FORGOT_PASSWORD).build();
    }
}
