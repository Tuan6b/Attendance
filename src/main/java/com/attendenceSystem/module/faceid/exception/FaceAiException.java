package com.attendenceSystem.module.faceid.exception;

/**
 * Ngoại lệ cơ sở cho các lỗi liên quan đến dịch vụ Face AI (FastAPI).
 */
public class FaceAiException extends RuntimeException {

    public FaceAiException(String message) {
        super(message);
    }

    public FaceAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
