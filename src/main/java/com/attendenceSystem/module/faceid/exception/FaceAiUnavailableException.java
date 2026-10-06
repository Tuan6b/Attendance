package com.attendenceSystem.module.faceid.exception;

/**
 * Ngoại lệ khi dịch vụ Face AI không khả dụng (kết nối thất bại, timeout, hoặc lỗi 5xx).
 */
public class FaceAiUnavailableException extends FaceAiException {

    public FaceAiUnavailableException(String message) {
        super(message);
    }

    public FaceAiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
