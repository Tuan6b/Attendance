package com.attendenceSystem.exception.custom;

/** Dữ liệu đã tồn tại / xung đột trạng thái (vd: trùng username, email, đơn đã được xử lý). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
