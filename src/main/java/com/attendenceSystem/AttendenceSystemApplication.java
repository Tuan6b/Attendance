package com.attendenceSystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
// Cần cho @Async ở FaceIdLogServiceImpl — thiếu annotation này thì @Async bị bỏ qua hoàn toàn,
// khiến việc ghi log nhận diện chạy đồng bộ ngay trên thread xử lý điểm danh (lỗi ghi log có
// thể kéo sập luôn transaction điểm danh chính, xem FaceIdRecognition/FaceIdLogServiceImpl).
@EnableAsync
public class AttendenceSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(AttendenceSystemApplication.class, args);
	}

}
