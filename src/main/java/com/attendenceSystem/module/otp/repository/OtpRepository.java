package com.attendenceSystem.module.otp.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.attendenceSystem.module.otp.entity.Otp;
import com.attendenceSystem.module.otp.entity.enums.OtpPurpose;

@Repository
public interface OtpRepository extends JpaRepository<Otp, Long> {

    Optional<Otp> findTopByDestinationAndPurposeAndUsedFalseOrderByCreatedAtDesc(
            String destination, OtpPurpose purpose);

    Optional<Otp> findTopByDestinationAndPurposeOrderByCreatedAtDesc(
            String destination, OtpPurpose purpose);

    @Modifying
    @Query("UPDATE Otp o SET o.used = true WHERE o.destination = :destination AND o.purpose = :purpose")
    void invalidateByDestinationAndPurpose(
            @Param("destination") String destination,
            @Param("purpose") OtpPurpose purpose);

    /**
     * Ghi nhận 1 lần nhập mã, nguyên tử: trả 0 khi đã hết lượt (request song song cũng không vượt quá max).
     * REQUIRES_NEW: lượt đếm phải được lưu kể cả khi transaction bên ngoài rollback vì nhập sai.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE Otp o SET o.attempts = o.attempts + 1 WHERE o.id = :id AND o.attempts < :max")
    int registerAttempt(@Param("id") Long id, @Param("max") int max);

    @Modifying
    @Query("DELETE FROM Otp o WHERE o.expiredAt < :now")
    void deleteExpiredOtps(@Param("now") Instant now);
}